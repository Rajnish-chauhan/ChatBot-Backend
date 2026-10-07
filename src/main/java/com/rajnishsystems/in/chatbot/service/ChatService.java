package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.tool.TavilyWebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    private final TavilyWebSearchTool tavilyWebSearchTool;

    @Value("classpath:prompts/promptTemplate.st")
    private Resource ragPromptTemplate;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       @Value("${tavily.api-key}") String tavilyApiKey) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.tavilyWebSearchTool = new TavilyWebSearchTool(tavilyApiKey);
    }

    public Flux<String> processAndStreamMessage(Long sessionId, String userText, String username) {

        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        // FIX: Dynamically update the session title in the database on the first message
        if (session.getTitle() == null || session.getTitle().equals("New Chat")) {
            String newTitle = userText.length() > 25 ? userText.substring(0, 25) + "..." : userText;
            session.setTitle(newTitle);
            sessionRepository.save(session);
        }

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSession(session);
        userMessage.setText(userText);
        userMessage.setSender("USER");
        messageRepository.save(userMessage);

        List<ChatMessage> chatHistory = messageRepository.findBySessionIdOrderByIdAsc(sessionId);

        StringBuilder historyBuilder = new StringBuilder();
        if (chatHistory.size() > 1) {
            historyBuilder.append("--- RECENT CONVERSATION HISTORY ---\n");
            int startIndex = Math.max(0, chatHistory.size() - 11);

            for (int i = startIndex; i < chatHistory.size() - 1; i++) {
                ChatMessage msg = chatHistory.get(i);
                String role = "USER".equalsIgnoreCase(msg.getSender()) ? username : "AI";
                historyBuilder.append(role).append(": ").append(msg.getText()).append("\n\n");
            }
            historyBuilder.append("--- END OF HISTORY ---\n\n");
        }

        String contextualQuestion = "System Note: The current user you are speaking to is named '" + username + "'.\n\n"
                + historyBuilder.toString()
                + username + " asks: " + userText;

        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder().query(userText).topK(3).build()
        );

        String documentContext = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        String finalPrompt = promptTemplate.render(Map.of(
                "context", documentContext,
                "question", contextualQuestion
        ));

        StringBuilder fullBotResponse = new StringBuilder();

        return chatClient.prompt()
                .user(finalPrompt)
                .tools(this.tavilyWebSearchTool)
                .stream()
                .content()
                .doOnNext(fullBotResponse::append)
                .doOnComplete(() -> {
                    try {
                        ChatSession freshSession = sessionRepository.findById(sessionId).orElse(null);
                        if (freshSession != null) {
                            ChatMessage botMessage = new ChatMessage();
                            botMessage.setSession(freshSession);
                            botMessage.setText(fullBotResponse.toString());
                            botMessage.setSender("BOT");
                            messageRepository.save(botMessage);
                        }
                    } catch (Exception e) {
                        System.err.println("Error saving AI message: " + e.getMessage());
                    }
                });
    }
}