package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.config.AiToolsConfig.TavilyWebSearcher;
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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final TavilyWebSearcher tavilyWebSearcher;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource ragPromptTemplate;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       TavilyWebSearcher tavilyWebSearcher) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.tavilyWebSearcher = tavilyWebSearcher;
    }

    @Transactional
    public Flux<String> processAndStreamMessage(Long sessionId, String userText) {
        // 1. Fetch session and save the user's message immediately
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSession(session);
        userMessage.setText(userText);
        userMessage.setSender("USER");
        messageRepository.save(userMessage);

        // 2. RAG Retrieval
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userText)
                        .topK(3)
                        .build()
        );

        String documentContext = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        String finalPrompt = promptTemplate.render(Map.of(
                "context", documentContext,
                "question", userText
        ));

        // 3. StringBuilder to collect chunks as they stream to the user
        StringBuilder fullBotResponse = new StringBuilder();

        // 4. Stream the response
        return chatClient.prompt()
                .user(finalPrompt)
                .tools(tavilyWebSearcher)
                .stream()
                .content()
                .doOnNext(fullBotResponse::append) // Add each chunk to our builder as it passes through
                .doOnComplete(() -> {
                    // 5. Save the complete message to the database once the stream finishes
                    ChatMessage botMessage = new ChatMessage();
                    botMessage.setSession(session);
                    botMessage.setText(fullBotResponse.toString());
                    botMessage.setSender("BOT");
                    messageRepository.save(botMessage);
                });
    }
}