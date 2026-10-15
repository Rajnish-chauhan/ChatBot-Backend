package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.dto.ChatRequest;
import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;

    public ChatController(ChatService chatService,
                          ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          UserRepository userRepository) {
        this.chatService = chatService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> getSessions(@AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);
        return ResponseEntity.ok(sessionRepository.findByUserOrderByIdAsc(user));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(@AuthenticationPrincipal UserDetails userDetails,
                                                     @RequestBody(required = false) Map<String, String> body) {
        User user = getUser(userDetails);
        String title = (body != null && body.get("title") != null && !body.get("title").isBlank())
                ? body.get("title").trim()
                : "New Chat";

        ChatSession session = new ChatSession();
        session.setTitle(title);
        session.setUser(user);
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getSessionMessages(@AuthenticationPrincipal UserDetails userDetails,
                                                                @PathVariable Long sessionId) {
        User user = getUser(userDetails);
        validateSessionOwnership(user, sessionId);
        return ResponseEntity.ok(messageRepository.findBySessionIdOrderByIdAsc(sessionId));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSession> renameSession(@AuthenticationPrincipal UserDetails userDetails,
                                                     @PathVariable Long sessionId,
                                                     @RequestBody Map<String, String> body) {
        User user = getUser(userDetails);
        validateSessionOwnership(user, sessionId);
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        String newTitle = body.get("title");
        if (newTitle != null && !newTitle.isBlank()) {
            session.setTitle(newTitle.trim());
            sessionRepository.save(session);
        }
        return ResponseEntity.ok(session);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@AuthenticationPrincipal UserDetails userDetails,
                                              @PathVariable Long sessionId) {
        User user = getUser(userDetails);
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to delete session");
        }
        sessionRepository.delete(session);
        return ResponseEntity.noContent().build();
    }

    // Multipart streaming endpoint for single or multiple file uploads (up to 3 files, max 20 MB total)
    @PostMapping(value = "/{sessionId}/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMessageWithFile(@AuthenticationPrincipal UserDetails userDetails,
                                              @PathVariable Long sessionId,
                                              @RequestParam(value = "text", required = false) String text,
                                              @RequestParam(value = "message", required = false) String messageFallback,
                                              @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        try {
            User user = getUser(userDetails);
            validateSessionOwnership(user, sessionId);
            String finalText = (text != null && !text.isBlank()) ? text : (messageFallback != null ? messageFallback : "");
            return chatService.processAndStreamMessage(sessionId, finalText, files, user.getUsername());
        } catch (Throwable ex) {
            log.error("Multipart stream error for session {}: ", sessionId, ex);
            return Flux.just("Unable to process the uploaded files: " + (ex.getMessage() != null ? ex.getMessage() : "Unexpected error."));
        }
    }

    // JSON streaming endpoint for text-only prompts
    @PostMapping(value = "/{sessionId}/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMessageJsonOnly(@AuthenticationPrincipal UserDetails userDetails,
                                              @PathVariable Long sessionId,
                                              @RequestBody ChatRequest request) {
        try {
            User user = getUser(userDetails);
            validateSessionOwnership(user, sessionId);
            String text = (request != null) ? request.effectiveText() : "";
            return chatService.processAndStreamMessage(sessionId, text, null, user.getUsername());
        } catch (Throwable ex) {
            log.error("JSON stream error for session {}: ", sessionId, ex);
            return Flux.just("Unable to process your request: " + (ex.getMessage() != null ? ex.getMessage() : "Unexpected error."));
        }
    }

    private void validateSessionOwnership(User user, Long sessionId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this session");
        }
    }

    private User getUser(UserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication token required");
        }
        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}