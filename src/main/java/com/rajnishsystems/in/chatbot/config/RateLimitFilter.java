package com.rajnishsystems.in.chatbot.config;

import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final UserRepository userRepository;
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/api/chat/") || !path.endsWith("/stream")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = auth.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        boolean isGuest = (user != null) && user.isGuest();

        // Bind guests to IP + device identifier to prevent quota bypass via localStorage clearing
        String rateLimitKey;
        if (isGuest) {
            String clientIp = extractClientIp(request);
            String deviceId = request.getHeader("X-Device-Id");
            rateLimitKey = "guest_limit:" + clientIp + ":" + (deviceId != null ? deviceId : "unknown_device");
        } else {
            rateLimitKey = "user_limit:" + username;
        }

        long capacity = isGuest ? 10 : 50;
        Bucket bucket = userBuckets.computeIfAbsent(rateLimitKey, k -> createNewBucket(capacity));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exhausted for key: {} (isGuest: {})", rateLimitKey, isGuest);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");

            String jsonResponse = isGuest
                    ? "{\"error\": \"LIMIT_REACHED\", \"isGuest\": true, \"message\": \"Daily limit reached. Please create a free account or sign in to continue chatting.\"}"
                    : "{\"error\": \"LIMIT_REACHED\", \"isGuest\": false, \"message\": \"Daily limit reached. Your quota will reset after 24 hours. Please try again tomorrow.\"}";

            response.getWriter().write(jsonResponse);
        }
    }

    // Extract client IP behind Cloudflare or standard reverse proxies
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                ip = ip.split(",")[0].trim();
            }
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return (ip != null && !ip.isBlank()) ? ip : "127.0.0.1";
    }

    private Bucket createNewBucket(long capacity) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(capacity, Duration.ofDays(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}