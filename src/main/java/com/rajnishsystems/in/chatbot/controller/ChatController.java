package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.dto.ChatRequest;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public ChatController(ChatService chatService,
                          ChatSessionRepository sessionRepository,
                          UserRepository userRepository) {
        this.chatService = chatService;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> getSessions(@AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);
        return ResponseEntity.ok(sessionRepository.findByUserOrderByIdAsc(user));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(@AuthenticationPrincipal UserDetails userDetails,
                                                     @RequestBody ChatSession sessionRequest) {
        User user = getUser(userDetails);
        sessionRequest.setId(null);
        sessionRequest.setUser(user);
        return ResponseEntity.ok(sessionRepository.save(sessionRequest));
    }

    // New Server-Sent Events (SSE) Streaming Endpoint
    @PostMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMessageRealTime(@AuthenticationPrincipal UserDetails userDetails,
                                              @PathVariable Long sessionId,
                                              @RequestBody ChatRequest request) {
        User user = getUser(userDetails);

        // Security check to ensure the user owns this session
        sessionRepository.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this session"));

        // Trigger the reactive streaming service
        return chatService.processAndStreamMessage(sessionId, request.text());
    }

    private User getUser(UserDetails userDetails) {
        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}