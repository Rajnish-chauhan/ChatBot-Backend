package com.rajnishsystems.in.chatbot.service;

import com.rajnishsystems.in.chatbot.model.ChatMessage;
import com.rajnishsystems.in.chatbot.model.ChatSession;
import com.rajnishsystems.in.chatbot.repository.ChatMessageRepository;
import com.rajnishsystems.in.chatbot.repository.ChatSessionRepository;
import com.rajnishsystems.in.chatbot.tool.TavilyWebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
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

import java.util.ArrayList;
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

    // Custom record to avoid Spring AI 'Media' version conflict
    private record ImageAttachment(MimeType mimeType, Resource resource) {}

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

    public Flux<String> processAndStreamMessage(Long sessionId, String userText, List<MultipartFile> files, String username) {

        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getTitle() == null || session.getTitle().equals("New Chat")) {
            String newTitle = userText.length() > 25 ? userText.substring(0, 25) + "..." : userText;
            session.setTitle(newTitle);
            sessionRepository.save(session);
        }

        // Save User Message
        ChatMessage userMessageEntity = new ChatMessage();
        userMessageEntity.setSession(session);
        String savedText = userText;
        if (files != null && !files.isEmpty()) {
            savedText += " [Attached " + files.size() + " Files]";
        }
        userMessageEntity.setText(savedText);
        userMessageEntity.setSender("USER");
        messageRepository.save(userMessageEntity);

        // Fetch Recent History (Memory Context)
        List<ChatMessage> chatHistory = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        StringBuilder historyBuilder = new StringBuilder();
        if (chatHistory.size() > 1) {
            historyBuilder.append("--- PREVIOUS CONVERSATION CONTEXT (REMEMBER THIS FOR CONTINUITY) ---\n");
            int startIndex = Math.max(0, chatHistory.size() - 11); // Last 10 messages
            for (int i = startIndex; i < chatHistory.size() - 1; i++) {
                ChatMessage msg = chatHistory.get(i);
                String role = "USER".equalsIgnoreCase(msg.getSender()) ? username : "AI";
                historyBuilder.append(role).append(": ").append(msg.getText()).append("\n\n");
            }
            historyBuilder.append("--- END OF PREVIOUS CONVERSATION ---\n\n");
        }

        // 1. Vector Database RAG search
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder().query(userText).topK(3).build()
        );
        String documentContext = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 2. Uploaded Files Processing (Multiple files)
        List<ImageAttachment> imageAttachments = new ArrayList<>();

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                try {
                    String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                    String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

                    Resource fileResource = new ByteArrayResource(file.getBytes()) {
                        @Override
                        public String getFilename() { return file.getOriginalFilename(); }
                    };

                    if (contentType.startsWith("video/") || contentType.startsWith("audio/") ||
                            originalFilename.endsWith(".mp4") || originalFilename.endsWith(".mp3") || originalFilename.endsWith(".wav")) {
                        documentContext += "\n\n[SYSTEM NOTE: User tried to upload audio/video file (" + file.getOriginalFilename() + "). Politely inform them you only support images and docs.]";

                    } else if (contentType.startsWith("image/") || originalFilename.endsWith(".png") || originalFilename.endsWith(".jpg") || originalFilename.endsWith(".jpeg") || originalFilename.endsWith(".webp")) {
                        MimeType imageMimeType = originalFilename.endsWith(".png") ? MimeTypeUtils.IMAGE_PNG : MimeTypeUtils.IMAGE_JPEG;
                        imageAttachments.add(new ImageAttachment(imageMimeType, fileResource));
                        documentContext += "\n\n--- USER UPLOADED IMAGE --- (" + file.getOriginalFilename() + " - analyze this image carefully)";

                    } else if (contentType.equals("application/pdf") || originalFilename.endsWith(".pdf")) {
                        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(fileResource);
                        String extractedText = pdfReader.get().stream().map(Document::getText).collect(Collectors.joining("\n"));
                        documentContext += "\n\n--- DOCUMENT CONTENT FROM USER (" + file.getOriginalFilename() + ") ---\n" + extractedText;

                    } else {
                        TikaDocumentReader documentReader = new TikaDocumentReader(fileResource);
                        String extractedText = documentReader.get().stream().map(Document::getText).collect(Collectors.joining("\n"));
                        documentContext += "\n\n--- DOCUMENT CONTENT FROM USER (" + file.getOriginalFilename() + ") ---\n" + extractedText;
                    }
                } catch (Exception e) {
                    System.err.println("File parsing error: " + e.getMessage());
                }
            }
        }

        // 3. Render System Prompt
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(ragPromptTemplate);
        String renderedSystemPrompt = promptTemplate.render(Map.of(
                "context", documentContext,
                "question", userText
        ));

        // 4. Build Final User Prompt
        String tempPromptText = "System Note: User name is '" + username + "'. Use the previous conversation context to remember past interactions.\n\n"
                + historyBuilder.toString()
                + username + " asks: " + userText;

        if (files != null && !files.isEmpty()) {
            tempPromptText += "\n(Note: Prioritize your answer based on the " + files.size() + " attached files and images).";
        }

        final String finalUserPromptText = tempPromptText;

        // 5. Spring AI ChatClient stream call
        StringBuilder fullBotResponse = new StringBuilder();

        return chatClient.prompt()
                .system(renderedSystemPrompt)
                .tools(this.tavilyWebSearchTool)
                .user(u -> {
                    u.text(finalUserPromptText);
                    for (ImageAttachment img : imageAttachments) {
                        u.media(img.mimeType(), img.resource());
                    }
                })
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