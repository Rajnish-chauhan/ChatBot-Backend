package com.rajnishsystems.in.chatbot.dto;

public record ChangePasswordRequest(
        String oldPassword,
        String newPassword
) {}