package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.config.JwtUtils;
import com.rajnishsystems.in.chatbot.dto.*;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.OtpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final OtpService otpService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtils jwtUtils,
                          OtpService otpService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.otpService = otpService;
    }

    @GetMapping("/check-email")
    public ResponseEntity<Map<String, Boolean>> checkEmail(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        boolean exists = userRepository.existsByEmail(email.trim().toLowerCase());
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @PostMapping("/send-otp")
    public ResponseEntity<String> sendOtp(@RequestBody SendOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }
        if (userRepository.existsByEmail(request.email().trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This email is already registered. Please log in.");
        }
        otpService.sendOtp(request.email().trim());
        return ResponseEntity.ok("OTP sent successfully");
    }

    @PostMapping("/register-with-otp")
    public ResponseEntity<?> registerWithOtp(@RequestBody RegisterWithOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }
        if (request.username() == null || request.username().isBlank()) {
            return ResponseEntity.badRequest().body("Username is required.");
        }
        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body("Password is required.");
        }

        String cleanEmail = request.email().trim().toLowerCase();
        String cleanUsername = request.username().trim();

        // 1. Validate email and username existence before consuming OTP
        if (userRepository.existsByEmail(cleanEmail)) {
            return ResponseEntity.badRequest().body("Email is already registered. Please log in.");
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            return ResponseEntity.badRequest().body("This username is already taken. Please choose a different one.");
        }

        // 2. Validate OTP code
        if (!otpService.verifyOtp(cleanEmail, request.otp())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid or expired OTP code.");
        }

        // 3. Persist new user
        User user = new User();
        user.setEmail(cleanEmail);
        user.setUsername(cleanUsername);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        user.setGuest(false);
        userRepository.save(user);

        // 4. Safely clear OTP after successful account creation
        otpService.clearOtp(cleanEmail);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), new ArrayList<>()
        );
        String token = jwtUtils.generateToken(userDetails);

        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), false, false));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            UserDetails userDetails = (UserDetails) auth.getPrincipal();
            User user = userRepository.findByUsername(userDetails.getUsername())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

            String token = jwtUtils.generateToken(userDetails);
            return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), !user.isPasswordSet(), user.isGuest()));

        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password. Please verify your credentials and try again.");
        }
    }

    @PostMapping("/guest")
    public ResponseEntity<AuthResponse> guestLogin() {
        String guestUsername = "guest_" + UUID.randomUUID().toString().substring(0, 8);

        User guestUser = new User();
        guestUser.setUsername(guestUsername);
        guestUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        guestUser.setGuest(true);
        guestUser.setPasswordSet(false);
        userRepository.save(guestUser);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                guestUser.getUsername(), guestUser.getPassword(), new ArrayList<>()
        );

        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, guestUser.getUsername(), false, true));
    }

    @PostMapping("/set-credentials")
    public ResponseEntity<AuthResponse> setCredentials(@AuthenticationPrincipal UserDetails userDetails,
                                                       @RequestBody SetCredentialsRequest request) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.getUsername().equals(request.username()) && userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This username is already taken. Please choose a different one.");
        }

        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        userRepository.save(user);

        UserDetails updatedDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), new ArrayList<>()
        );

        String newToken = jwtUtils.generateToken(updatedDetails);
        return ResponseEntity.ok(new AuthResponse(newToken, user.getUsername(), false, user.isGuest()));
    }

    @PostMapping("/upgrade-guest")
    public ResponseEntity<AuthResponse> upgradeGuest(@AuthenticationPrincipal UserDetails userDetails,
                                                     @RequestBody UpgradeGuestRequest request) {
        User guestUser = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Guest user not found"));

        String cleanEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        String cleanUsername = request.username() != null ? request.username().trim() : "";

        if (userRepository.existsByEmail(cleanEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already linked to another account.");
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This username is already taken. Please choose a different one.");
        }
        if (!otpService.verifyOtp(cleanEmail, request.otp())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP code.");
        }

        guestUser.setEmail(cleanEmail);
        guestUser.setUsername(cleanUsername);
        guestUser.setPassword(passwordEncoder.encode(request.password()));
        guestUser.setPasswordSet(true);
        guestUser.setGuest(false);
        userRepository.save(guestUser);

        otpService.clearOtp(cleanEmail);

        UserDetails updatedDetails = new org.springframework.security.core.userdetails.User(
                guestUser.getUsername(), guestUser.getPassword(), new ArrayList<>()
        );

        String newToken = jwtUtils.generateToken(updatedDetails);
        return ResponseEntity.ok(new AuthResponse(newToken, guestUser.getUsername(), false, false));
    }
}