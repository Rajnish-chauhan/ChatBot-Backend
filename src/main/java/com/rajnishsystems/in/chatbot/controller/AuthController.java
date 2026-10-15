package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.config.JwtUtils;
import com.rajnishsystems.in.chatbot.dto.*;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final OtpService otpService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtils jwtUtils,
                          OtpService otpService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.otpService = otpService;
    }

    // Real-time email availability check during registration
    @GetMapping("/check-email")
    public ResponseEntity<Map<String, Boolean>> checkEmail(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        boolean exists = userRepository.existsByEmail(email.trim().toLowerCase());
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    // Real-time username availability check during registration and OAuth setup
    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Boolean>> checkUsername(@RequestParam String username) {
        if (username == null || username.isBlank()) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        boolean exists = userRepository.existsByUsername(username.trim());
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    // Send 6-digit OTP for new account registration
    @PostMapping("/send-otp")
    public ResponseEntity<?> sendOtp(@RequestBody SendOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }

        String cleanEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(cleanEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This email is already registered. Please log in or use Forgot Password."));
        }

        try {
            otpService.sendOtp(cleanEmail);
            return ResponseEntity.ok(Map.of("message", "OTP sent successfully to " + cleanEmail));
        } catch (Exception ex) {
            log.error("Failed to send OTP to {}: {}", cleanEmail, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send verification email. Please check mail credentials."));
        }
    }

    // Verify OTP code before allowing username and password selection
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtpOnly(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        String otp = payload.get("otp");

        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and OTP are required."));
        }

        boolean isValid = otpService.verifyOtp(email.trim().toLowerCase(), otp.trim());
        if (!isValid) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid or expired OTP code."));
        }

        return ResponseEntity.ok(Map.of("message", "OTP verified successfully."));
    }

    // Direct registration endpoint
    @PostMapping("/register")
    public ResponseEntity<?> registerDirect(@RequestBody RegisterWithOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }
        if (request.username() == null || request.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (request.password() == null || request.password().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters."));
        }

        String cleanEmail = request.email().trim().toLowerCase();
        String cleanUsername = request.username().trim();

        if (userRepository.existsByEmail(cleanEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This email is already registered. Please log in."));
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This username is already taken. Please choose another."));
        }

        User user = new User();
        user.setEmail(cleanEmail);
        user.setUsername(cleanUsername);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        user.setGuest(false);
        userRepository.save(user);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                new ArrayList<>()
        );
        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), false, false));
    }

    // Complete registration with verified OTP, unique username, and password
    @PostMapping("/register-with-otp")
    public ResponseEntity<?> registerWithOtp(@RequestBody RegisterWithOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }
        if (request.username() == null || request.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (request.password() == null || request.password().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters."));
        }

        String cleanEmail = request.email().trim().toLowerCase();
        String cleanUsername = request.username().trim();

        if (userRepository.existsByEmail(cleanEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This email is already registered. Please log in."));
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This username is already taken. Please choose another."));
        }

        if (!otpService.verifyOtp(cleanEmail, request.otp())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid or expired OTP code."));
        }

        User user = new User();
        user.setEmail(cleanEmail);
        user.setUsername(cleanUsername);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        user.setGuest(false);
        userRepository.save(user);

        otpService.clearOtp(cleanEmail);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                new ArrayList<>()
        );
        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), false, false));
    }

    // Send OTP for password recovery
    @PostMapping("/forgot-password/send-otp")
    public ResponseEntity<?> sendForgotPasswordOtp(@RequestBody SendOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }

        String cleanEmail = request.email().trim().toLowerCase();
        if (!userRepository.existsByEmail(cleanEmail)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No registered account found with this email address."));
        }

        try {
            otpService.sendOtp(cleanEmail);
            return ResponseEntity.ok(Map.of("message", "Password reset code sent to " + cleanEmail));
        } catch (Exception ex) {
            log.error("Failed to send password reset OTP to {}: {}", cleanEmail, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send password reset email."));
        }
    }

    // Reset account password using OTP verification
    @PostMapping("/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        String otp = payload.get("otp");
        String newPassword = payload.get("newPassword");

        if (email == null || email.isBlank() || otp == null || otp.isBlank() || newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Valid email, 6-digit OTP, and a new password (min 6 characters) are required."));
        }

        String cleanEmail = email.trim().toLowerCase();
        if (!otpService.verifyOtp(cleanEmail, otp.trim())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid or expired OTP code."));
        }

        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));

        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        user.setPasswordSet(true);
        userRepository.save(user);
        otpService.clearOtp(cleanEmail);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully. You can now sign in."));
    }

    // Authenticate existing user via username or email
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        String identifier = request.effectiveUsername();
        if (identifier.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username or email is required."));
        }
        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required."));
        }

        User user = userRepository.findByUsernameOrEmail(identifier, identifier.toLowerCase())
                .orElse(null);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No account found with this username or email. Please register."));
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Incorrect password. Please try again."));
        }

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                new ArrayList<>()
        );
        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), !user.isPasswordSet(), user.isGuest()));
    }

    // Provision a temporary guest user bound to client device ID
    @PostMapping("/guest")
    public ResponseEntity<AuthResponse> guestLogin(HttpServletRequest request) {
        String deviceId = request.getHeader("X-Device-Id");
        String sanitizedId = (deviceId != null && deviceId.length() >= 6)
                ? deviceId.substring(0, Math.min(10, deviceId.length()))
                : UUID.randomUUID().toString().substring(0, 8);

        String guestUsername = "guest_" + sanitizedId;

        User guestUser = userRepository.findByUsername(guestUsername).orElseGet(() -> {
            User newGuest = new User();
            newGuest.setUsername(guestUsername);
            newGuest.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            newGuest.setGuest(true);
            newGuest.setPasswordSet(false);
            return userRepository.save(newGuest);
        });

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                guestUser.getUsername(),
                guestUser.getPassword(),
                new ArrayList<>()
        );

        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, guestUser.getUsername(), false, true));
    }

    // Finalize username and password setup for OAuth2 users
    @PostMapping("/set-credentials")
    @Transactional
    public ResponseEntity<AuthResponse> setCredentials(@AuthenticationPrincipal UserDetails userDetails,
                                                       @RequestBody SetCredentialsRequest request) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized user.");
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String cleanUsername = request.username() != null ? request.username().trim() : "";
        if (cleanUsername.isBlank() || request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and valid password (min 6 characters) are required.");
        }

        if (!user.getUsername().equals(cleanUsername) && userRepository.existsByUsername(cleanUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken.");
        }

        user.setUsername(cleanUsername);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        userRepository.save(user);

        UserDetails updatedDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                new ArrayList<>()
        );
        String newToken = jwtUtils.generateToken(updatedDetails);
        return ResponseEntity.ok(new AuthResponse(newToken, user.getUsername(), false, user.isGuest()));
    }
}