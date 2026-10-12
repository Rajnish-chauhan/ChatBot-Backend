package com.rajnishsystems.in.chatbot.dto;

public record UserProfileResponse(
        Long id,
        String username,
        String email,
        boolean isGuest,
        boolean isPasswordSet,
        String profilePic
) {}