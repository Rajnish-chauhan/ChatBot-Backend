package com.rajnishsystems.in.chatbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rajnishsystems.in.chatbot.config.JwtUtils;
import com.rajnishsystems.in.chatbot.dto.AuthRequest;
import com.rajnishsystems.in.chatbot.dto.RegisterWithOtpRequest;
import com.rajnishsystems.in.chatbot.dto.SendOtpRequest;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private OtpService otpService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    @DisplayName("GET /api/auth/check-email - Should return true when email exists")
    void testCheckEmailExists() throws Exception {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        mockMvc.perform(get("/api/auth/check-email")
                        .param("email", "test@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    @DisplayName("POST /api/auth/send-otp - Should return 200 when OTP is sent")
    void testSendOtpSuccess() throws Exception {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        doNothing().when(otpService).sendOtp("new@example.com");

        SendOtpRequest request = new SendOtpRequest("new@example.com");

        mockMvc.perform(post("/api/auth/send-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/auth/register-with-otp - Should successfully register new user")
    void testRegisterWithOtpSuccess() throws Exception {
        RegisterWithOtpRequest request = new RegisterWithOtpRequest(
                "new@example.com", "123456", "newuser", "password123"
        );

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(otpService.verifyOtp("new@example.com", "123456")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(jwtUtils.generateToken(any(UserDetails.class))).thenReturn("mock-jwt-token");

        mockMvc.perform(post("/api/auth/register-with-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-jwt-token"))
                .andExpect(jsonPath("$.username").value("newuser"));

        verify(otpService, times(1)).clearOtp("new@example.com");
    }

    @Test
    @DisplayName("POST /api/auth/register-with-otp - Should reject when username already exists")
    void testRegisterWithOtpUsernameTaken() throws Exception {
        RegisterWithOtpRequest request = new RegisterWithOtpRequest(
                "new@example.com", "123456", "existingUser", "password123"
        );

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.existsByUsername("existingUser")).thenReturn(true);

        mockMvc.perform(post("/api/auth/register-with-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify OTP is NOT cleared so the user can re-try with another username
        verify(otpService, never()).clearOtp(anyString());
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return JWT token on valid credentials")
    void testLoginSuccess() throws Exception {
        AuthRequest request = new AuthRequest("rajnish", "password123");

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "rajnish", "password123", new ArrayList<>()
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        User mockUser = new User();
        mockUser.setUsername("rajnish");
        mockUser.setPasswordSet(true);
        mockUser.setGuest(false);

        when(userRepository.findByUsername("rajnish")).thenReturn(Optional.of(mockUser));
        when(jwtUtils.generateToken(userDetails)).thenReturn("valid-jwt-token");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("valid-jwt-token"))
                .andExpect(jsonPath("$.username").value("rajnish"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 401 on bad credentials")
    void testLoginBadCredentials() throws Exception {
        AuthRequest request = new AuthRequest("rajnish", "wrongPassword");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/guest - Should create and return guest session token")
    void testGuestLogin() throws Exception {
        when(passwordEncoder.encode(anyString())).thenReturn("hashedPassword");
        when(jwtUtils.generateToken(any(UserDetails.class))).thenReturn("guest-jwt-token");

        mockMvc.perform(post("/api/auth/guest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("guest-jwt-token"))
                .andExpect(jsonPath("$.isGuest").value(true));
    }
}