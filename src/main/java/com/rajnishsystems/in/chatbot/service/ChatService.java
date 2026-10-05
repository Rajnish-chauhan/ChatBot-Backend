package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource ragPromptTemplate;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ChatMessage processAndSaveMessage(Long sessionId, String userText) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        // 1. Save the incoming user message
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSession(session);
        userMessage.setText(userText);
        userMessage.setSender("USER");
        // Removed setCreatedAt to allow your entity's @CreationTimestamp or DB defaults to handle it
        messageRepository.save(userMessage);

        // 2. Generate RAG + Tool-augmented AI response
        String aiResponseText = generateResponse(userText);

        // 3. Save and return the assistant message
        ChatMessage botMessage = new ChatMessage();
        botMessage.setSession(session);
        botMessage.setText(aiResponseText);
        botMessage.setSender("BOT");

        return messageRepository.save(botMessage);
    }

    public String generateResponse(String userQuestion) {
        // FIX 1: Use the correct Spring AI 2.0+ Builder syntax for the vector search
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userQuestion)
                        .topK(3)
                        .build()
        );

        // FIX 2: Use Document::getText instead of Document::getContent
        String documentContext = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        System.out.println("--- RAG CONTEXT SENT TO LLM ---");
        System.out.println("PDF Chunks found: " + similarDocuments.size());

        // PROMPT STEP: Inject RAG context into the template
        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        String finalPrompt = promptTemplate.render(Map.of(
                "context", documentContext,
                "question", userQuestion
        ));

        // FIX 3: Use .tools() instead of .function() or .functions() for Spring AI 2.0+
        return chatClient.prompt()
                .user(finalPrompt)
                .tools("tavilySearch")
                .call()
                .content();
    }
}