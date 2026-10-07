package com.rajnishsystems.in.chatbot.dto;

public record UpgradeGuestRequest(
        String email,
        String otp,
        String username,
        String password
) {}