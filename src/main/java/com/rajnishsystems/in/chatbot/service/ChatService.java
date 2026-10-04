package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource ragPromptResource;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    @Transactional
    public ChatMessage processAndSaveMessage(Long sessionId, String userPrompt) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        // 1. Save user prompt to PostgreSQL
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSession(session);
        userMessage.setSender("user");
        userMessage.setText(userPrompt);
        messageRepository.save(userMessage);

        // 2. Similarity search in pgvector using the new builder pattern
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userPrompt)
                        .topK(3)
                        .similarityThreshold(0.7)
                        .build()
        );

        // 3. Extract text from the chunks
        String contextData = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 4. Format dynamic prompt using rag-prompt.st
        String aiResponseText = chatClient.prompt()
                .user(userSpec -> userSpec.text(ragPromptResource)
                        .param("context", contextData)
                        .param("question", userPrompt))
                .call()
                .content();

        // 5. Save LLM response to PostgreSQL
        ChatMessage botMessage = new ChatMessage();
        botMessage.setSession(session);
        botMessage.setSender("bot");
        botMessage.setText(aiResponseText);

        return messageRepository.save(botMessage);
    }
}