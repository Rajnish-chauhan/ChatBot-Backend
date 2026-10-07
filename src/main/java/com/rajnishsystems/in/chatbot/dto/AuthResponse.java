package com.rajnishsystems.in.chatbot.dto;

public record AuthResponse(
        String token,
        String username,
        boolean requiresPasswordSetup,
        boolean isGuest
) {}