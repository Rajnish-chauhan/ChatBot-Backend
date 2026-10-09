package com.rajnishsystems.in.chatbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ChatService chatService;

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ChatController chatController;

    private User mockUser;
    private UserDetails mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("rajnish");

        mockUserDetails = new org.springframework.security.core.userdetails.User(
                "rajnish", "password", new ArrayList<>()
        );

        // Custom resolver to mock @AuthenticationPrincipal UserDetails parameter
        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().isAssignableFrom(UserDetails.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return mockUserDetails;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(chatController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
    }

    @Test
    @DisplayName("GET /api/chat/sessions - Should return all sessions of the authenticated user")
    void testGetSessions() throws Exception {
        ChatSession session = new ChatSession();
        session.setId(101L);
        session.setTitle("Project Discussion");

        when(userRepository.findByUsername("rajnish")).thenReturn(Optional.of(mockUser));
        when(sessionRepository.findByUserOrderByIdAsc(mockUser)).thenReturn(List.of(session));

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].title").value("Project Discussion"));
    }

    @Test
    @DisplayName("POST /api/chat/sessions - Should successfully create a new chat session")
    void testCreateSession() throws Exception {
        ChatSession newSessionRequest = new ChatSession();
        newSessionRequest.setTitle("New Chat");

        ChatSession savedSession = new ChatSession();
        savedSession.setId(202L);
        savedSession.setTitle("New Chat");
        savedSession.setUser(mockUser);

        when(userRepository.findByUsername("rajnish")).thenReturn(Optional.of(mockUser));
        when(sessionRepository.save(any(ChatSession.class))).thenReturn(savedSession);

        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newSessionRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(202))
                .andExpect(jsonPath("$.title").value("New Chat"));
    }
}