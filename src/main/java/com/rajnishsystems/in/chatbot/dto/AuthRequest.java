package com.rajnishsystems.in.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Authentication credentials supporting both 'username' and 'emailOrUsername' JSON properties.
 * Done
 */
public record AuthRequest(
        @JsonAlias("emailOrUsername")
        String username,
        String password
) {
    public String effectiveUsername() {
        return (username != null && !username.isBlank()) ? username.trim() : "";
    }
}