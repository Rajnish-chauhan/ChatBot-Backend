package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final ChatSessionRepository sessionRepository;

    public UserController(UserRepository userRepository, ChatSessionRepository sessionRepository) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    @DeleteMapping("/me")
    @Transactional
    public ResponseEntity<Void> deleteMyAccount(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 1. Delete all sessions first to clear foreign key constraints (messages delete via cascade)
        sessionRepository.deleteAll(sessionRepository.findByUserOrderByIdAsc(user));

        // 2. Delete the user
        userRepository.delete(user);

        return ResponseEntity.noContent().build();
    }
}