package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.config.JwtUtils;
import com.rajnishsystems.in.chatbot.dto.*;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.OtpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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

    @PostMapping("/send-otp")
    public ResponseEntity<String> sendOtp(@RequestBody SendOtpRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }
        otpService.sendOtp(request.email().trim());
        return ResponseEntity.ok("OTP sent successfully to " + request.email());
    }

    @PostMapping("/register-with-otp")
    public ResponseEntity<?> registerWithOtp(@RequestBody RegisterWithOtpRequest request) {
        if (!otpService.verifyOtp(request.email(), request.otp())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid or expired OTP code.");
        }

        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.badRequest().body("Username is already taken.");
        }

        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.badRequest().body("Email is already registered.");
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase());
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordSet(true);
        user.setGuest(false);
        userRepository.save(user);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), new ArrayList<>()
        );
        String token = jwtUtils.generateToken(userDetails);

        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), false, false));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), !user.isPasswordSet(), user.isGuest()));
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username already taken");
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

        if (!otpService.verifyOtp(request.email(), request.otp())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP code.");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already linked to another account.");
        }

        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username already taken.");
        }

        guestUser.setEmail(request.email().toLowerCase());
        guestUser.setUsername(request.username());
        guestUser.setPassword(passwordEncoder.encode(request.password()));
        guestUser.setPasswordSet(true);
        guestUser.setGuest(false);
        userRepository.save(guestUser);

        UserDetails updatedDetails = new org.springframework.security.core.userdetails.User(
                guestUser.getUsername(), guestUser.getPassword(), new ArrayList<>()
        );

        String newToken = jwtUtils.generateToken(updatedDetails);
        return ResponseEntity.ok(new AuthResponse(newToken, guestUser.getUsername(), false, false));
    }
}