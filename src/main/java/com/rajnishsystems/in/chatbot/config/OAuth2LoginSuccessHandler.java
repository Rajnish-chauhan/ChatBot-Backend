package com.rajnishsystems.in.chatbot.config;

import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OAuth2LoginSuccessHandler(JwtUtils jwtUtils, UserRepository userRepository) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

            // Extract email safely and convert to lowercase
            String email = oAuth2User.getAttribute("email");
            if (email == null || email.isBlank()) {
                String login = oAuth2User.getAttribute("login");
                email = (login != null ? login : "user_" + UUID.randomUUID().toString().substring(0, 6)) + "@google.com";
            }
            final String cleanEmail = email.trim().toLowerCase();

            // Check if user already exists or safely create a new one
            User user = userRepository.findByEmail(cleanEmail).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(cleanEmail);

                // Generate a guaranteed unique username to avoid database constraint violations
                String baseUsername = cleanEmail.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
                String candidateUsername = baseUsername;
                while (userRepository.existsByUsername(candidateUsername)) {
                    candidateUsername = baseUsername + "_" + UUID.randomUUID().toString().substring(0, 4);
                }

                newUser.setUsername(candidateUsername);
                newUser.setPassword(UUID.randomUUID().toString()); // Placeholder password
                newUser.setPasswordSet(false);
                newUser.setGuest(false);
                return userRepository.save(newUser);
            });

            // Create Spring Security UserDetails instance with non-null password
            org.springframework.security.core.userdetails.User userDetails =
                    new org.springframework.security.core.userdetails.User(
                            user.getUsername(),
                            user.getPassword() != null ? user.getPassword() : UUID.randomUUID().toString(),
                            new ArrayList<>()
                    );

            String token = jwtUtils.generateToken(userDetails);

            // Redirect back to frontend with generated JWT token
            String frontendUrl = String.format("http://localhost:5173/oauth2/redirect?token=%s&username=%s&requiresPasswordSetup=%s",
                    token,
                    URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8),
                    !user.isPasswordSet());

            log.info("OAuth2 login successful for user: {}. Redirecting to frontend.", user.getUsername());
            getRedirectStrategy().sendRedirect(request, response, frontendUrl);

        } catch (Exception ex) {
            log.error("Error processing OAuth2 login success: ", ex);
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "OAuth2 authentication error";
            getRedirectStrategy().sendRedirect(request, response, "http://localhost:5173/?error=" + URLEncoder.encode(errorMsg, StandardCharsets.UTF_8));
        }
    }
}