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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // 20 MB maximum limit across uploaded files
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final TavilyWebSearchTool tavilyWebSearchTool;

    @Value("${app.prompt.template:classpath:prompts/promptTemplate.st}")
    private Resource ragPromptTemplate;

    private record ImageAttachment(String filename, MimeType mimeType, Resource resource) {}

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
        try {
            ChatSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

            // Validate single-file and cumulative 20 MB limit on the server side
            if (files != null && !files.isEmpty()) {
                long runningBytes = 0L;
                for (MultipartFile file : files) {
                    if (file == null || file.isEmpty()) continue;
                    String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Uploaded file";
                    double fileMb = file.getSize() / (1024.0 * 1024.0);

                    if (file.getSize() > MAX_FILE_SIZE_BYTES) {
                        return Flux.just(String.format(
                                "File size too long: \"%s\" (%.1f MB) exceeds the maximum allowed limit of 20 MB and was not uploaded.",
                                fileName, fileMb
                        ));
                    }
                    if (runningBytes + file.getSize() > MAX_FILE_SIZE_BYTES) {
                        double totalMb = (runningBytes + file.getSize()) / (1024.0 * 1024.0);
                        return Flux.just(String.format(
                                "File size too long: \"%s\" (%.1f MB) exceeds the 20 MB total upload limit (total would be %.1f MB) and was not uploaded.",
                                fileName, fileMb, totalMb
                        ));
                    }
                    runningBytes += file.getSize();
                }
            }

            String safeUserText = (userText != null && !userText.isBlank())
                    ? userText.trim()
                    : "Please analyze and explain each attached file in detail.";

            if (session.getTitle() == null || session.getTitle().equalsIgnoreCase("New Chat")) {
                String newTitle = safeUserText.length() > 25 ? safeUserText.substring(0, 25) + "..." : safeUserText;
                session.setTitle(newTitle);
                sessionRepository.save(session);
            }

            ChatMessage userMessageEntity = new ChatMessage();
            userMessageEntity.setSession(session);
            String savedText = safeUserText;
            if (files != null && !files.isEmpty()) {
                savedText += " [Attached " + Math.min(files.size(), 3) + " File(s)]";
            }
            userMessageEntity.setText(savedText);
            userMessageEntity.setSender("USER");
            messageRepository.save(userMessageEntity);

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

            StringBuilder documentContextBuilder = new StringBuilder();
            try {
                if (vectorStore != null) {
                    String lowerQuery = safeUserText.toLowerCase();
                    boolean isPolicyQuery = lowerQuery.contains("policy")
                            || lowerQuery.contains("privacy")
                            || lowerQuery.contains("terms")
                            || lowerQuery.contains("rule")
                            || lowerQuery.contains("data")
                            || lowerQuery.contains("limit")
                            || lowerQuery.contains("quota");

                    String effectiveSearchQuery = isPolicyQuery
                            ? "ChatBot Privacy Policy Usage Policy acceptable use data collection protection security user rights quota " + safeUserText
                            : safeUserText;

                    double threshold = isPolicyQuery ? 0.25 : 0.50;

                    List<Document> similarDocuments = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(effectiveSearchQuery)
                                    .topK(3)
                                    .similarityThreshold(threshold)
                                    .build()
                    );

                    if (similarDocuments != null && !similarDocuments.isEmpty()) {
                        String vectorContext = similarDocuments.stream()
                                .map(Document::getText)
                                .collect(Collectors.joining("\n\n---\n\n"));
                        documentContextBuilder.append("--- RELEVANT INTERNAL POLICY CONTEXT ---\n")
                                .append(vectorContext)
                                .append("\n\n");
                    }
                }
            } catch (Throwable e) {
                log.warn("Vector search skipped: {}", e.getMessage());
            }

            // Read and prepare up to 3 uploaded files (PDF, PPTX, DOCX, XLSX, TXT, and Images)
            List<ImageAttachment> imageAttachments = new ArrayList<>();
            if (files != null && !files.isEmpty()) {
                int filesCount = Math.min(files.size(), 3);
                documentContextBuilder.append("--- USER UPLOADED FILES (TOTAL: ")
                        .append(filesCount)
                        .append(") ---\n");

                for (int i = 0; i < filesCount; i++) {
                    MultipartFile file = files.get(i);
                    if (file == null || file.isEmpty()) continue;

                    int fileIndex = i + 1;
                    String rawName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                            ? file.getOriginalFilename()
                            : "attachment_" + fileIndex;
                    String lowerName = rawName.toLowerCase();
                    String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

                    try {
                        byte[] fileBytes = file.getBytes();

                        MimeType detectedImageMime = detectImageMimeType(fileBytes, lowerName, contentType);
                        if (detectedImageMime != null) {
                            ImageAttachment optimizedImg = prepareImageForVision(fileBytes, rawName, detectedImageMime, imageAttachments.size() + 1);
                            imageAttachments.add(optimizedImg);
                            documentContextBuilder.append(String.format(
                                    "\n[FILE #%d - IMAGE: \"%s\"]\n" +
                                            "Attached as Vision Image #%d. Read all visible text, marks, or visual details in this image and explain it clearly under \"%s\".\n",
                                    fileIndex, rawName, imageAttachments.size(), rawName
                            ));
                            continue;
                        }

                        Resource fileResource = new ByteArrayResource(fileBytes) {
                            @Override
                            public String getFilename() {
                                return rawName;
                            }
                        };

                        if (lowerName.endsWith(".txt")
                                || lowerName.endsWith(".md")
                                || lowerName.endsWith(".csv")
                                || lowerName.endsWith(".json")
                                || lowerName.endsWith(".xml")
                                || lowerName.endsWith(".html")
                                || lowerName.endsWith(".log")
                                || contentType.startsWith("text/")) {
                            String textContent = new String(fileBytes, StandardCharsets.UTF_8).trim();
                            String excerpt = textContent.length() > 7000
                                    ? textContent.substring(0, 7000) + "\n...[truncated]"
                                    : textContent;
                            documentContextBuilder.append(String.format(
                                    "\n[FILE #%d - TEXT DOCUMENT: \"%s\"]\n%s\n[END OF FILE #%d]\n",
                                    fileIndex, rawName, excerpt, fileIndex
                            ));
                            continue;
                        }

                        String extractedText = extractDocumentText(fileResource, lowerName, contentType, safeUserText);
                        documentContextBuilder.append(String.format(
                                "\n[FILE #%d - DOCUMENT: \"%s\"]\n%s\n[END OF FILE #%d]\n",
                                fileIndex, rawName, extractedText, fileIndex
                        ));

                    } catch (Throwable fileErr) {
                        log.error("Failed to process attachment {}: {}", rawName, fileErr.getMessage());
                        documentContextBuilder.append(String.format(
                                "\n[FILE #%d: \"%s\"]\n[Could not extract content from this file.]\n",
                                fileIndex, rawName
                        ));
                    }
                }
            }

            String rawContext = documentContextBuilder.toString().trim();
            if (rawContext.isBlank()) {
                rawContext = "No specific internal context or file data provided for this query.";
            }
            String safeContext = sanitizeTemplateBraces(rawContext);
            String safeQueryForTemplate = sanitizeTemplateBraces(safeUserText);

            String renderedSystemPrompt;
            try {
                if (ragPromptTemplate != null && ragPromptTemplate.exists()) {
                    SystemPromptTemplate promptTemplate = new SystemPromptTemplate(ragPromptTemplate);
                    renderedSystemPrompt = promptTemplate.render(Map.of(
                            "context", safeContext,
                            "query", safeQueryForTemplate
                    ));
                } else {
                    renderedSystemPrompt = "You are an expert AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + safeContext;
                }
            } catch (Throwable e) {
                log.error("Failed to render prompt template: {}", e.getMessage());
                renderedSystemPrompt = "You are an expert AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + safeContext;
            }

            final String finalSysPrompt = sanitizeTemplateBraces(renderedSystemPrompt);
            StringBuilder userPromptBuilder = new StringBuilder();
            userPromptBuilder.append(sanitizeTemplateBraces(historyBuilder.toString()));
            if (!imageAttachments.isEmpty()) {
                userPromptBuilder.append("Attached Vision Images in order:\n");
                for (int i = 0; i < imageAttachments.size(); i++) {
                    userPromptBuilder.append("- Image #").append(i + 1).append(": \"").append(imageAttachments.get(i).filename()).append("\"\n");
                }
                userPromptBuilder.append("Analyze both the document text in CONTEXT and every attached Vision Image above.\n\n");
            }
            userPromptBuilder.append(username).append(": ").append(safeQueryForTemplate);
            final String finalUserPromptText = userPromptBuilder.toString();

            StringBuilder fullBotResponse = new StringBuilder();

            var promptSpec = chatClient.prompt().system(finalSysPrompt);

            if (this.tavilyWebSearchTool != null && (files == null || files.isEmpty())) {
                promptSpec.tools(this.tavilyWebSearchTool);
            }

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
                        return Flux.just("I encountered an error while processing your request: "
                                + (ex.getMessage() != null ? ex.getMessage() : "AI engine unavailable."));
                    })
                    .doOnNext(fullBotResponse::append)
                    .doOnComplete(() -> {
                        try {
                            ChatSession freshSession = sessionRepository.findById(sessionId).orElse(null);
                            if (freshSession != null && !fullBotResponse.isEmpty()) {
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
                                }
                            }
                        } catch (Throwable e) {
                            log.error("Failed to persist bot reply: ", e);
                        }
                    });
        } catch (Throwable fatalEx) {
            log.error("Fatal error in processAndStreamMessage: ", fatalEx);
            return Flux.just("An unexpected error occurred while reading your files: "
                    + (fatalEx.getMessage() != null ? fatalEx.getMessage() : "Please try again."));
        }
    }

    private ImageAttachment prepareImageForVision(byte[] rawBytes, String rawName, MimeType detectedMime, int index) {
        String safeResourceName = "image_" + index;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original != null) {
                int maxDim = 1280;
                int width = original.getWidth();
                int height = original.getHeight();

                double scale = Math.min(1.0, Math.min((double) maxDim / width, (double) maxDim / height));
                int targetW = Math.max(1, (int) Math.round(width * scale));
                int targetH = Math.max(1, (int) Math.round(height * scale));

                BufferedImage rgbImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = rgbImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, targetW, targetH);
                g2d.drawImage(original, 0, 0, targetW, targetH, null);
                g2d.dispose();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
                if (writers.hasNext()) {
                    ImageWriter writer = writers.next();
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(0.82f);
                    }
                    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                        writer.setOutput(ios);
                        writer.write(null, new IIOImage(rgbImage, null, null), param);
                    } finally {
                        writer.dispose();
                    }
                } else {
                    ImageIO.write(rgbImage, "jpg", out);
                }

                byte[] compressedBytes = out.toByteArray();
                Resource optimizedResource = new ByteArrayResource(compressedBytes) {
                    @Override
                    public String getFilename() {
                        return safeResourceName;
                    }
                };
                return new ImageAttachment(rawName, MimeTypeUtils.IMAGE_JPEG, optimizedResource);
            }
        } catch (Throwable ex) {
            log.debug("Image optimization skipped for {}: {}", rawName, ex.getMessage());
        }

        Resource fallbackResource = new ByteArrayResource(rawBytes) {
            @Override
            public String getFilename() {
                return safeResourceName;
            }
        };
        return new ImageAttachment(rawName, detectedMime, fallbackResource);
    }

    private MimeType detectImageMimeType(byte[] bytes, String lowerName, String contentType) {
        if (bytes != null && bytes.length >= 12) {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
                return MimeTypeUtils.IMAGE_PNG;
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
                return MimeTypeUtils.IMAGE_JPEG;
            }
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
                return MimeTypeUtils.IMAGE_GIF;
            }
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return MimeType.valueOf("image/webp");
            }
        }

        if (contentType.startsWith("image/")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif")) {
            if (lowerName.endsWith(".webp") || contentType.contains("webp")) {
                return MimeType.valueOf("image/webp");
            }
            if (lowerName.endsWith(".gif") || contentType.contains("gif")) {
                return MimeTypeUtils.IMAGE_GIF;
            }
            if (lowerName.endsWith(".png") || contentType.contains("png")) {
                return MimeTypeUtils.IMAGE_PNG;
            }
            return MimeTypeUtils.IMAGE_JPEG;
        }

        return null;
    }

    private String extractDocumentText(Resource fileResource, String lowerName, String contentType, String userQuery) {
        String extracted = "";

        try {
            List<Document> tikaDocs = new TikaDocumentReader(fileResource).get();
            extracted = tikaDocs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"))
                    .trim();
        } catch (Throwable tikaEx) {
            log.warn("Tika extraction warning for {}: {}", fileResource.getFilename(), tikaEx.getMessage());
        }

        if (extracted.isBlank() && (contentType.equals("application/pdf") || lowerName.endsWith(".pdf"))) {
            try {
                List<Document> pdfDocs = new PagePdfDocumentReader(fileResource).get();
                extracted = pdfDocs.stream()
                        .map(Document::getText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n\n"))
                        .trim();
            } catch (Throwable pdfEx) {
                log.warn("PagePdfDocumentReader fallback warning for {}: {}", fileResource.getFilename(), pdfEx.getMessage());
            }
        }

        if (extracted.isBlank()) {
            return "[No readable text could be extracted from this file.]";
        }

        String normalized = extracted.replaceAll("\\n{3,}", "\n\n").trim();

        if (normalized.length() <= 7000) {
            return normalized;
        }

        String introSection = normalized.substring(0, 3500);
        List<String> keywords = Arrays.stream(userQuery.toLowerCase().split("\\s+"))
                .filter(w -> w.length() > 3)
                .toList();

        String[] paragraphs = normalized.substring(3500).split("\\n\\n+");
        String matchedParagraphs = Arrays.stream(paragraphs)
                .sorted(Comparator.comparingInt((String p) -> {
                    String lower = p.toLowerCase();
                    int score = 0;
                    for (String kw : keywords) {
                        if (lower.contains(kw)) score++;
                    }
                    return score;
                }).reversed())
                .limit(5)
                .collect(Collectors.joining("\n\n"));

        String combined = introSection + "\n\n...[Relevant Excerpts]...\n\n" + matchedParagraphs;
        return combined.length() > 7500 ? combined.substring(0, 7500) + "\n...[truncated]" : combined;
    }

    private String sanitizeTemplateBraces(String input) {
        if (input == null) return "";
        return input.replace("{", "(").replace("}", ")");
    }
}