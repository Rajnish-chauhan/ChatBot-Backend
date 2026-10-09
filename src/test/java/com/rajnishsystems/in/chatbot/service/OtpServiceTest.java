package com.rajnishsystems.in.chatbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private OtpService otpService;

    private final String testEmail = "test@example.com";

    @Test
    @DisplayName("Should send email successfully when sending OTP")
    void testSendOtp() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> otpService.sendOtp(testEmail));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Should verify valid OTP successfully without prematurely clearing it")
    @SuppressWarnings("unchecked")
    void testVerifyOtpValid() throws Exception {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        otpService.sendOtp(testEmail);

        // Access internal otpStorage map to retrieve generated OTP for assertion
        Field storageField = OtpService.class.getDeclaredField("otpStorage");
        storageField.setAccessible(true);
        Map<String, ?> storage = (Map<String, ?>) storageField.get(otpService);

        Object otpData = storage.get(testEmail);
        assertNotNull(otpData);

        Field otpField = otpData.getClass().getDeclaredField("otp");
        otpField.setAccessible(true);
        String generatedOtp = (String) otpField.get(otpData);

        // First verification should pass
        boolean isFirstAttemptValid = otpService.verifyOtp(testEmail, generatedOtp);
        assertTrue(isFirstAttemptValid);

        // Second verification should also pass (not prematurely deleted)
        boolean isSecondAttemptValid = otpService.verifyOtp(testEmail, generatedOtp);
        assertTrue(isSecondAttemptValid);
    }

    @Test
    @DisplayName("Should return false for incorrect OTP")
    void testVerifyOtpInvalid() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        otpService.sendOtp(testEmail);

        boolean isValid = otpService.verifyOtp(testEmail, "999999");
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should clear OTP from storage when clearOtp is called")
    @SuppressWarnings("unchecked")
    void testClearOtp() throws Exception {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        otpService.sendOtp(testEmail);

        otpService.clearOtp(testEmail);

        Field storageField = OtpService.class.getDeclaredField("otpStorage");
        storageField.setAccessible(true);
        Map<String, ?> storage = (Map<String, ?>) storageField.get(otpService);

        assertNull(storage.get(testEmail));
    }
}