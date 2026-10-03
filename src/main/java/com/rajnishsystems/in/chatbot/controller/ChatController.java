package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.dto.ChatResponse; // <-- FIXED IMPORT
import com.rajnishsystems.in.chatbot.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<ChatResponse> chat(
            @RequestParam("message") String message,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            String aiResponse = chatService.processRequest(message, file);
            return ResponseEntity.ok(new ChatResponse(aiResponse));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ChatResponse("Error: " + e.getMessage()));
        }
    }
}