package com.rajnishsystems.in.chatbot.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    private JwtUtils jwtUtils;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        userDetails = new User("rajnish", "password123", new ArrayList<>());
    }

    @Test
    @DisplayName("Should successfully generate a valid JWT token")
    void testGenerateToken() {
        String token = jwtUtils.generateToken(userDetails);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertTrue(jwtUtils.validateJwtToken(token));
    }

    @Test
    @DisplayName("Should extract correct username from JWT token")
    void testGetUserNameFromJwtToken() {
        String token = jwtUtils.generateToken(userDetails);
        String extractedUsername = jwtUtils.getUserNameFromJwtToken(token);

        assertEquals("rajnish", extractedUsername);
    }

    @Test
    @DisplayName("Should return false for malformed or invalid token")
    void testValidateJwtTokenInvalid() {
        String invalidToken = "thisIsAnInvalidTokenString.12345.xyz";

        boolean isValid = jwtUtils.validateJwtToken(invalidToken);

        assertFalse(isValid);
    }
}