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

    public Flux<String> processAndStreamMessage(Long sessionId, String userText, MultipartFile file, String username) {

        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getTitle() == null || session.getTitle().equals("New Chat")) {
            String newTitle = userText.length() > 25 ? userText.substring(0, 25) + "..." : userText;
            session.setTitle(newTitle);
            sessionRepository.save(session);
        }

        // Database me user message save karna
        ChatMessage userMessageEntity = new ChatMessage();
        userMessageEntity.setSession(session);
        String savedText = userText;
        if (file != null && !file.isEmpty()) {
            savedText += " [Attached File: " + file.getOriginalFilename() + "]";
        }
        userMessageEntity.setText(savedText);
        userMessageEntity.setSender("USER");
        messageRepository.save(userMessageEntity);

        // Recent conversation history fetch karna
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

        // 1. Vector Database RAG search
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder().query(userText).topK(3).build()
        );
        String documentContext = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 2. Uploaded File Processing
        boolean isImage = false;
        MimeType imageMimeType = null;
        Resource imageResource = null;

        if (file != null && !file.isEmpty()) {
            try {
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

                Resource fileResource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return file.getOriginalFilename();
                    }
                };

                // Video ya Audio files ko reject karna
                if (contentType.startsWith("video/") || contentType.startsWith("audio/") ||
                        originalFilename.endsWith(".mp4") || originalFilename.endsWith(".mp3") || originalFilename.endsWith(".wav") || originalFilename.endsWith(".mkv")) {
                    documentContext += "\n\n--- UPLOADED FILE STATUS ---\n[SYSTEM NOTE: User ne audio ya video file upload ki hai. Please politely inform karein ki aap video aur audio files process nahi kar sakte.]";

                    // Image processing (PNG, JPG, JPEG, WEBP)
                } else if (contentType.startsWith("image/") || originalFilename.endsWith(".png") || originalFilename.endsWith(".jpg") || originalFilename.endsWith(".jpeg") || originalFilename.endsWith(".webp")) {
                    isImage = true;
                    if (originalFilename.endsWith(".png")) {
                        imageMimeType = MimeTypeUtils.IMAGE_PNG;
                    } else {
                        imageMimeType = MimeTypeUtils.IMAGE_JPEG;
                    }
                    imageResource = fileResource;
                    documentContext += "\n\n--- UPLOADED IMAGE NOTE ---\nUser ne ek image attach ki hai (" + file.getOriginalFilename() + "). Please image ke visual contents ko read aur analyze karke answer karein.";

                    // PDF document processing
                } else if (contentType.equals("application/pdf") || originalFilename.endsWith(".pdf")) {
                    PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(fileResource);
                    List<Document> extractedDocs = pdfReader.get();
                    String extractedText = extractedDocs.stream()
                            .map(Document::getText)
                            .collect(Collectors.joining("\n"));

                    documentContext += "\n\n--- UPLOADED PDF CONTENT (" + file.getOriginalFilename() + ") ---\n" + extractedText;

                    // Baaki documents (TXT, DOCX, CSV)
                } else {
                    TikaDocumentReader documentReader = new TikaDocumentReader(fileResource);
                    List<Document> extractedDocs = documentReader.get();
                    String extractedText = extractedDocs.stream()
                            .map(Document::getText)
                            .collect(Collectors.joining("\n"));

                    documentContext += "\n\n--- UPLOADED FILE CONTENT (" + file.getOriginalFilename() + ") ---\n" + extractedText;
                }

            } catch (Exception e) {
                System.err.println("File parsing error: " + e.getMessage());
                documentContext += "\n\n--- SYSTEM ERROR ---\nUploaded file read karne me dikkat aayi: " + e.getMessage();
            }
        }

        // 3. System Prompt render karna
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(ragPromptTemplate);
        String renderedSystemPrompt = promptTemplate.render(Map.of(
                "context", documentContext,
                "question", userText
        ));

        // 4. User Question prompt build karna
        String tempPromptText = "System Note: User ka naam '" + username + "' hai.\n"
                + historyBuilder.toString()
                + username + " asks: " + userText;

        if (file != null && !file.isEmpty()) {
            tempPromptText += "\n(Note: User ne upar di gayi file/image ke baare me pucha hai, context ko prioritize karke explain karein.)";
        }

        // FIX: Copy to a final variable so it can be used in the lambda expression safely
        final String finalUserPromptText = tempPromptText;

        // 5. Spring AI ChatClient stream call
        StringBuilder fullBotResponse = new StringBuilder();
        final boolean finalIsImage = isImage;
        final MimeType finalImageMimeType = imageMimeType;
        final Resource finalImageResource = imageResource;

        return chatClient.prompt()
                .system(renderedSystemPrompt)
                .tools(this.tavilyWebSearchTool)
                .user(u -> {
                    u.text(finalUserPromptText);
                    if (finalIsImage) {
                        u.media(finalImageMimeType, finalImageResource);
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