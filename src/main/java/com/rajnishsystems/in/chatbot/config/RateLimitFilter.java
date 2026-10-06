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

        // 1. Only enforce rate limits on chat endpoints
        if (!request.getRequestURI().startsWith("/api/chat")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = auth.getName();

        // 2. Fetch or create the user's bucket
        Bucket bucket = userBuckets.computeIfAbsent(username, this::createNewBucket);

        // 3. Consume a token and get the remaining count
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // INTERNAL BACKEND MONITORING ONLY - Never sent to the frontend
            log.info("API Call Successful | User: {} | Calls Remaining Today: {}", username, probe.getRemainingTokens());

            filterChain.doFilter(request, response);
        } else {
            log.warn("API Limit Reached | User: {}", username);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Daily limit reached. Please upgrade your account or try again tomorrow.");
        }
    }

    private Bucket createNewBucket(String username) {
        User user = userRepository.findByUsername(username).orElse(null);

        boolean isGuest = (user != null) && user.isGuest();
        long capacity = isGuest ? 10 : 100;

        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(capacity, Duration.ofDays(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}