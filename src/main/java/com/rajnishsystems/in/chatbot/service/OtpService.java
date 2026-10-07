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
    private static final long OTP_VALIDITY_SECONDS = 300; // 5 minutes

    private final JavaMailSender mailSender;
    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();

    private record OtpData(String otp, Instant expiresAt) {}

    public OtpService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtp(String email) {
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        otpStorage.put(email.toLowerCase(), new OtpData(otp, Instant.now().plusSeconds(OTP_VALIDITY_SECONDS)));

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("Your ChatBot Verification Code");
            message.setText("Welcome to ChatBot!\n\nYour 6-digit OTP code is: " + otp + "\n\nThis code expires in 5 minutes. Do not share this code with anyone.");
            mailSender.send(message);
            log.info("OTP successfully sent to email: {}", email);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", email, e.getMessage());
            throw new RuntimeException("Could not send verification email. Please check your email configuration.");
        }
    }

    public boolean verifyOtp(String email, String inputOtp) {
        String cleanEmail = email.toLowerCase();
        OtpData data = otpStorage.get(cleanEmail);

        if (data == null) {
            return false;
        }

        if (Instant.now().isAfter(data.expiresAt())) {
            otpStorage.remove(cleanEmail);
            return false;
        }

        boolean isValid = data.otp().equals(inputOtp.trim());
        if (isValid) {
            otpStorage.remove(cleanEmail);
        }
        return isValid;
    }
}