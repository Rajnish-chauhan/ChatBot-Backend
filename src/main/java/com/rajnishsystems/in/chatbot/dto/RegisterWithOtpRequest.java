package com.rajnishsystems.in.chatbot.dto;

public record RegisterWithOtpRequest(
        String email,
        String otp,
        String username,
        String password
) {}