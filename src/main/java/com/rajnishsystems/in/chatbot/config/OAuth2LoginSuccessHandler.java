package com.rajnishsystems.in.chatbot.config;

import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    @Value("${app.frontend.url:http://localhost:5173}")
    private String configuredFrontendUrl;

    public OAuth2LoginSuccessHandler(JwtUtils jwtUtils, UserRepository userRepository) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    // Automatically select localhost:5173 for local runs and chatbot.rajnishsystems.in for live runs
    private String resolveTargetFrontendUrl(HttpServletRequest request) {
        String serverName = request.getServerName() != null ? request.getServerName().toLowerCase() : "";
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = (forwardedHost != null && !forwardedHost.isBlank()) ? forwardedHost.toLowerCase() : serverName;

        if (host.contains("localhost") || host.contains("127.0.0.1")) {
            return "http://localhost:5173";
        }
        if (host.contains("rajnishsystems.in")) {
            return "https://chatbot.rajnishsystems.in";
        }
        String cleaned = configuredFrontendUrl != null ? configuredFrontendUrl.replaceAll("/+$", "") : "https://chatbot.rajnishsystems.in";
        if (cleaned.contains("localhost")) {
            return "https://chatbot.rajnishsystems.in";
        }
        return cleaned;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        String targetBaseUrl = resolveTargetFrontendUrl(request);

        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

            String email = oAuth2User.getAttribute("email");
            if (email == null || email.isBlank()) {
                String login = oAuth2User.getAttribute("login");
                email = (login != null ? login : "user_" + UUID.randomUUID().toString().substring(0, 6)) + "@google.com";
            }
            final String cleanEmail = email.trim().toLowerCase();

            User user = userRepository.findByEmail(cleanEmail).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(cleanEmail);

                String baseUsername = cleanEmail.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
                String candidateUsername = baseUsername;
                while (userRepository.existsByUsername(candidateUsername)) {
                    candidateUsername = baseUsername + "_" + UUID.randomUUID().toString().substring(0, 4);
                }

                newUser.setUsername(candidateUsername);
                newUser.setPassword(UUID.randomUUID().toString());
                newUser.setPasswordSet(false);
                newUser.setGuest(false);
                return userRepository.save(newUser);
            });

            org.springframework.security.core.userdetails.User userDetails =
                    new org.springframework.security.core.userdetails.User(
                            user.getUsername(),
                            user.getPassword() != null ? user.getPassword() : UUID.randomUUID().toString(),
                            new ArrayList<>()
                    );

            String token = jwtUtils.generateToken(userDetails);

            String frontendUrl = String.format("%s/?token=%s&username=%s%s",
                    targetBaseUrl,
                    token,
                    URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8),
                    (!user.isPasswordSet() ? "&new_oauth=true" : ""));

            log.info("OAuth2 login succeeded for user: {}. Redirecting to {}", user.getUsername(), targetBaseUrl);
            getRedirectStrategy().sendRedirect(request, response, frontendUrl);

        } catch (Exception ex) {
            log.error("OAuth2 authentication error: ", ex);
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "OAuth2 authentication error";
            getRedirectStrategy().sendRedirect(request, response, targetBaseUrl + "/?error=" + URLEncoder.encode(errorMsg, StandardCharsets.UTF_8));
        }
    }
}