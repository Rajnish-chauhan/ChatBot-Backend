package com.rajnishsystems.in.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    // 10 minutes validity window to prevent premature expirations
    private static final long OTP_VALIDITY_SECONDS = 600;

    private final JavaMailSender mailSender;
    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();

    private record OtpData(String otp, Instant expiresAt) {}

    public OtpService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtp(String email) {
        String cleanEmail = email.toLowerCase().trim();
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        otpStorage.put(cleanEmail, new OtpData(otp, Instant.now().plusSeconds(OTP_VALIDITY_SECONDS)));

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(cleanEmail);
            message.setSubject("Your ChatBot Verification Code");
            message.setText("Welcome to ChatBot!\n\nYour 6-digit OTP code is: " + otp + "\n\nThis code expires in 10 minutes. Do not share this code with anyone.");
            mailSender.send(message);
            log.info("OTP successfully sent to email: {}", cleanEmail);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", cleanEmail, e.getMessage());
            throw new RuntimeException("Could not send verification email. Please check mail credentials.");
        }
    }

    public boolean verifyOtp(String email, String inputOtp) {
        if (email == null || inputOtp == null) {
            return false;
        }

        String cleanEmail = email.toLowerCase().trim();
        OtpData data = otpStorage.get(cleanEmail);

        if (data == null) {
            return false;
        }

        if (Instant.now().isAfter(data.expiresAt())) {
            otpStorage.remove(cleanEmail);
            return false;
        }

        return data.otp().equals(inputOtp.trim());
    }

    public void clearOtp(String email) {
        if (email != null) {
            otpStorage.remove(email.toLowerCase().trim());
        }
    }
}