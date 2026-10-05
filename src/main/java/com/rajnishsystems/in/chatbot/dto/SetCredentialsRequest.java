package com.rajnishsystems.in.chatbot.dto;

public record SetCredentialsRequest(
        String username,
        String password
) {}