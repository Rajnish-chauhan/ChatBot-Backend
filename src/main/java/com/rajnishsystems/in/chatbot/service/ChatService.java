package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.model.User;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.repository.UserRepository;
import com.rajnishsystems.in.chatbot.tool.TavilyWebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final TavilyWebSearchTool tavilyWebSearchTool;

    @Value("${app.prompt.template:classpath:prompts/promptTemplate.st}")
    private Resource ragPromptTemplate;

    private record ImageAttachment(MimeType mimeType, Resource resource) {}

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       UserRepository userRepository,
                       @Value("${tavily.api.key:${tavily.api-key:}}") String tavilyApiKey) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.tavilyWebSearchTool = (tavilyApiKey != null && !tavilyApiKey.isBlank())
                ? new TavilyWebSearchTool(tavilyApiKey)
                : null;
    }

    public Flux<String> processAndStreamMessage(Long sessionId, String userText, List<MultipartFile> files, String username) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        String safeUserText = (userText != null && !userText.isBlank()) ? userText.trim() : "Please inspect the attached file(s).";

        // Generate title from the first query if session is new
        if (session.getTitle() == null || session.getTitle().equalsIgnoreCase("New Chat")) {
            String newTitle = safeUserText.length() > 25 ? safeUserText.substring(0, 25) + "..." : safeUserText;
            session.setTitle(newTitle);
            sessionRepository.save(session);
        }

        // Persist incoming user query
        ChatMessage userMessageEntity = new ChatMessage();
        userMessageEntity.setSession(session);
        String savedText = safeUserText;
        if (files != null && !files.isEmpty()) {
            savedText += " [Attached " + files.size() + " File(s)]";
        }
        userMessageEntity.setText(savedText);
        userMessageEntity.setSender("USER");
        messageRepository.save(userMessageEntity);

        // Retrieve conversation history up to 6 previous messages
        List<ChatMessage> chatHistory = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        StringBuilder historyBuilder = new StringBuilder();
        if (chatHistory.size() > 1) {
            historyBuilder.append("--- PREVIOUS CONVERSATION CONTEXT ---\n");
            int startIndex = Math.max(0, chatHistory.size() - 7);
            for (int i = startIndex; i < chatHistory.size() - 1; i++) {
                ChatMessage msg = chatHistory.get(i);
                String role = "USER".equalsIgnoreCase(msg.getSender()) ? username : "AI";
                historyBuilder.append(role).append(": ").append(msg.getText()).append("\n\n");
            }
            historyBuilder.append("--- END OF PREVIOUS CONVERSATION ---\n\n");
        }

        // Semantic document search with max 3 chunks retrieval
        StringBuilder documentContextBuilder = new StringBuilder();
        try {
            if (vectorStore != null) {
                List<Document> similarDocuments = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(safeUserText)
                                .topK(3)
                                .similarityThreshold(0.55)
                                .build()
                );
                if (similarDocuments != null && !similarDocuments.isEmpty()) {
                    String vectorContext = similarDocuments.stream()
                            .map(Document::getText)
                            .collect(Collectors.joining("\n\n---\n\n"));
                    documentContextBuilder.append("--- RELEVANT INTERNAL POLICY / DOCUMENT CONTEXT ---\n")
                            .append(vectorContext)
                            .append("\n\n");
                }
            }
        } catch (Exception e) {
            log.warn("Vector search failed or index is empty: {}", e.getMessage());
        }

        // Process file attachments (maximum of 3 files)
        List<ImageAttachment> imageAttachments = new ArrayList<>();
        if (files != null && !files.isEmpty()) {
            int filesCount = Math.min(files.size(), 3);
            for (int i = 0; i < filesCount; i++) {
                MultipartFile file = files.get(i);
                if (file.isEmpty()) continue;
                try {
                    String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                    String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

                    Resource fileResource = new ByteArrayResource(file.getBytes()) {
                        @Override
                        public String getFilename() { return file.getOriginalFilename(); }
                    };

                    if (contentType.startsWith("image/") || originalFilename.endsWith(".png") || originalFilename.endsWith(".jpg") || originalFilename.endsWith(".jpeg") || originalFilename.endsWith(".webp")) {
                        MimeType imageMimeType = originalFilename.endsWith(".png") ? MimeTypeUtils.IMAGE_PNG : MimeTypeUtils.IMAGE_JPEG;
                        imageAttachments.add(new ImageAttachment(imageMimeType, fileResource));
                    } else if (originalFilename.endsWith(".txt") || originalFilename.endsWith(".md") || contentType.startsWith("text/")) {
                        String textContent = new String(file.getBytes(), StandardCharsets.UTF_8);
                        String excerpt = textContent.length() > 4000 ? textContent.substring(0, 4000) + "\n...[truncated]" : textContent;
                        documentContextBuilder.append("\n--- CONTENT OF UPLOADED FILE (")
                                .append(file.getOriginalFilename())
                                .append(") ---\n")
                                .append(excerpt)
                                .append("\n");
                    } else if (contentType.equals("application/pdf") || originalFilename.endsWith(".pdf") || originalFilename.endsWith(".doc") || originalFilename.endsWith(".docx")) {
                        List<Document> rawDocs;
                        if (contentType.equals("application/pdf") || originalFilename.endsWith(".pdf")) {
                            rawDocs = new PagePdfDocumentReader(fileResource).get();
                        } else {
                            rawDocs = new TikaDocumentReader(fileResource).get();
                        }

                        TokenTextSplitter splitter = TokenTextSplitter.builder()
                                .withChunkSize(500)
                                .withMinChunkSizeChars(150)
                                .build();
                        List<Document> chunks = splitter.apply(rawDocs);

                        List<String> keywords = Arrays.stream(safeUserText.toLowerCase().split("\\s+"))
                                .filter(w -> w.length() > 3)
                                .toList();

                        List<Document> relevantChunks = chunks.stream()
                                .sorted(Comparator.comparingInt((Document doc) -> {
                                    String text = doc.getText().toLowerCase();
                                    int score = 0;
                                    for (String kw : keywords) {
                                        if (text.contains(kw)) score++;
                                    }
                                    return score;
                                }).reversed())
                                .limit(3)
                                .toList();

                        String chunkContext = relevantChunks.stream()
                                .map(Document::getText)
                                .collect(Collectors.joining("\n\n---\n\n"));

                        documentContextBuilder.append("\n--- EXTRACTED EXCERPTS FROM ATTACHED FILE (")
                                .append(file.getOriginalFilename())
                                .append(") ---\n")
                                .append(chunkContext)
                                .append("\n");
                    }
                } catch (Exception e) {
                    log.error("Failed to process attachment {}: {}", file.getOriginalFilename(), e.getMessage());
                }
            }
        }

        String finalContext = documentContextBuilder.toString().trim();
        if (finalContext.isBlank()) {
            finalContext = "No specific internal context or file data provided for this query.";
        }

        // Render system prompt from promptTemplate.st
        String renderedSystemPrompt;
        try {
            if (ragPromptTemplate != null && ragPromptTemplate.exists()) {
                SystemPromptTemplate promptTemplate = new SystemPromptTemplate(ragPromptTemplate);
                renderedSystemPrompt = promptTemplate.render(Map.of(
                        "context", finalContext,
                        "query", safeUserText
                ));
            } else {
                renderedSystemPrompt = "You are an expert AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + finalContext;
            }
        } catch (Exception e) {
            log.error("Failed to render prompt template: {}", e.getMessage());
            renderedSystemPrompt = "You are an expert AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + finalContext;
        }

        String finalUserPromptText = historyBuilder.toString() + username + ": " + safeUserText;
        final String finalSysPrompt = renderedSystemPrompt;
        StringBuilder fullBotResponse = new StringBuilder();

        var promptSpec = chatClient.prompt().system(finalSysPrompt);

        if (this.tavilyWebSearchTool != null) {
            promptSpec.tools(this.tavilyWebSearchTool);
        }

        // Execute prompt streaming with fallback error handling
        return promptSpec
                .user(u -> {
                    u.text(finalUserPromptText);
                    for (ImageAttachment img : imageAttachments) {
                        u.media(img.mimeType(), img.resource());
                    }
                })
                .stream()
                .content()
                .onErrorResume(ex -> {
                    log.error("AI streaming generation failure: ", ex);
                    String safeErrorMsg = "I encountered an error while processing your request: "
                            + (ex.getMessage() != null ? ex.getMessage() : "AI engine unavailable. Please verify API key configuration.");
                    return Flux.just(safeErrorMsg);
                })
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

                            User currentUser = userRepository.findByUsername(username).orElse(null);
                            if (currentUser != null) {
                                long promptTokens = (finalSysPrompt.length() + finalUserPromptText.length()) / 4;
                                long responseTokens = fullBotResponse.length() / 4;
                                long totalTokensSpent = Math.max(1, promptTokens + responseTokens);

                                currentUser.setTokensUsed(currentUser.getTokensUsed() + totalTokensSpent);
                                currentUser.setApiCallsCount(currentUser.getApiCallsCount() + 1);
                                userRepository.save(currentUser);

                                log.info("Token tracking: User [{}] used {} tokens. Total Tokens: {}, API Calls: {}",
                                        username, totalTokensSpent, currentUser.getTokensUsed(), currentUser.getApiCallsCount());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to persist bot reply and update token metrics: ", e);
                    }
                });
    }
}