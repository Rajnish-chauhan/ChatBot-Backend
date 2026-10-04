package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.config.JwtUtils;
import com.rajnishsystems.in.chatbot.dto.AuthRequest;
import com.rajnishsystems.in.chatbot.dto.AuthResponse;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtils jwtUtils) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            return ResponseEntity.badRequest().body("Username taken");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        return ResponseEntity.ok("User registered");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        return ResponseEntity.ok(new AuthResponse(token, userDetails.getUsername()));
    }

    @PostMapping("/guest")
    public ResponseEntity<AuthResponse> guestLogin() {
        String guestUsername = "guest_" + UUID.randomUUID().toString().substring(0, 8);

        User guestUser = new User();
        guestUser.setUsername(guestUsername);
        guestUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        userRepository.save(guestUser);

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                guestUser.getUsername(),
                guestUser.getPassword(),
                new ArrayList<>()
        );

        String token = jwtUtils.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, guestUser.getUsername()));
    }
}