package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.dto.ChangePasswordRequest;
import com.rajnishsystems.in.chatbot.dto.UserProfileResponse;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final ChatSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
                          ChatSessionRepository sessionRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // 1. Fetch current logged-in user profile
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return ResponseEntity.ok(new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isGuest(),
                user.isPasswordSet(),
                user.getProfilePic()
        ));
    }

    // 2. Update Profile Picture and/or Custom Username for OAuth users
    @PutMapping("/profile")
    @Transactional
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> payload) {

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String newProfilePic = payload.get("profilePic");
        if (newProfilePic != null && !newProfilePic.isBlank()) {
            user.setProfilePic(newProfilePic);
        }

        String newUsername = payload.get("username");
        if (newUsername != null && !newUsername.isBlank()) {
            String cleanUsername = newUsername.trim();
            if (!cleanUsername.equals(user.getUsername())) {
                if (userRepository.existsByUsername(cleanUsername)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This username is already taken. Please choose another.");
                }
                user.setUsername(cleanUsername);
            }
        }

        userRepository.save(user);

        return ResponseEntity.ok(new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isGuest(),
                user.isPasswordSet(),
                user.getProfilePic()
        ));
    }

    // 3. Change or Create Password
    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ChangePasswordRequest request) {

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (request.newPassword() == null || request.newPassword().trim().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 6 characters long.");
        }

        if (user.isPasswordSet()) {
            if (request.oldPassword() == null || request.oldPassword().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter your current password.");
            }
            if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
            }
            if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from your current password.");
            }
        }

        user.setPassword(passwordEncoder.encode(request.newPassword().trim()));
        user.setPasswordSet(true);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password saved successfully."));
    }

    // 4. Token & API usage stats query for admin / developer
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUserUsage(@RequestParam String username) {
        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : "N/A",
                "tokensUsed", user.getTokensUsed(),
                "apiCallsCount", user.getApiCallsCount()
        ));
    }

    // 5. Delete account permanently with required password verification
    @DeleteMapping("/me")
    @Transactional
    public ResponseEntity<Void> deleteMyAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(value = "password", required = false) String passwordParam,
            @RequestBody(required = false) Map<String, String> payload) {

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Extract password from query param or JSON payload
        String password = (passwordParam != null && !passwordParam.isBlank())
                ? passwordParam
                : (payload != null ? payload.get("password") : null);

        // Enforce password verification for registered users who have a password set
        if (user.isPasswordSet()) {
            if (password == null || password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter your password to confirm account deletion.");
            }
            if (!passwordEncoder.matches(password, user.getPassword())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password. Account deletion denied.");
            }
        }

        sessionRepository.deleteAll(sessionRepository.findByUserOrderByIdAsc(user));
        userRepository.delete(user);

        return ResponseEntity.noContent().build();
    }
}