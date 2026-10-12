package com.rajnishsystems.in.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Encapsulates incoming chat requests, supporting both 'text' and 'message' property names.
 */
public record ChatRequest(
        @JsonAlias({"message", "query", "prompt"})
        String text
) {
    public String effectiveText() {
        return (text != null && !text.isBlank()) ? text.trim() : "";
    }
}