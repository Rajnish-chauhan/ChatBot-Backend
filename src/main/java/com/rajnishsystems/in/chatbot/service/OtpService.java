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
    private static final long OTP_VALIDITY_SECONDS = 600; // 10 minutes

    private final JavaMailSender mailSender;
    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();

    private record OtpData(String otp, Instant expiresAt) {}

    public OtpService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtp(String email) {
        sendOtpEmail(
                email,
                "Your ChatBot Verification Code",
                "Welcome to ChatBot!\n\nYour 6-digit verification OTP code is: %s\n\nThis code is valid for 10 minutes. Please do not share this code with anyone."
        );
    }

    public void sendPasswordResetOtp(String email) {
        sendOtpEmail(
                email,
                "ChatBot Password Reset Code",
                "Hello,\n\nWe received a request to reset your ChatBot account password.\nYour 6-digit password reset OTP code is: %s\n\nThis code expires in 10 minutes. If you did not request a password reset, you can safely ignore this email."
        );
    }

    private void sendOtpEmail(String email, String subject, String bodyTemplate) {
        String cleanEmail = email.toLowerCase().trim();
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        otpStorage.put(cleanEmail, new OtpData(otp, Instant.now().plusSeconds(OTP_VALIDITY_SECONDS)));

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(cleanEmail);
            message.setSubject(subject);
            message.setText(String.format(bodyTemplate, otp));
            mailSender.send(message);
            log.info("OTP email sent successfully to: {}", cleanEmail);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", cleanEmail, e.getMessage());
            throw new RuntimeException("Could not send verification email. Please verify mail configuration.");
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