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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // Hard limits enforced in the backend: 20 MB file upload cap and 5,000 tokens maximum context window
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_CONTEXT_TOKENS = 5000;
    private static final int MAX_USER_MESSAGE_TOKENS = 2500;
    private static final int MAX_FILE_CONTEXT_TOKENS = 3200;
    private static final int MAX_HISTORY_TOKENS = 1000;

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

            if (session.getUsername() == null || !session.getUsername().equals(username)) {
                session.setUsername(username);
                session = sessionRepository.save(session);
            }

            String safeUserText = (userText != null && !userText.isBlank())
                    ? userText.trim()
                    : "Please analyze and explain each attached file concisely.";

            // 1. Check if the user's message alone exceeds the 5,000-token context limit
            int userPromptTokens = estimateTokens(safeUserText);
            if (userPromptTokens > MAX_USER_MESSAGE_TOKENS) {
                return Flux.just("Message too long: Your message exceeds the maximum allowed context window limit (5,000 tokens). Please shorten your message and try again.");
            }

            // 2. Check per-file and cumulative 20 MB byte size limit
            if (files != null && !files.isEmpty()) {
                long runningBytes = 0L;
                for (MultipartFile file : files) {
                    if (file == null || file.isEmpty()) continue;
                    String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Uploaded file";

                    if (file.getSize() > MAX_FILE_SIZE_BYTES || runningBytes + file.getSize() > MAX_FILE_SIZE_BYTES) {
                        return Flux.just(String.format(
                                "File too long: \"%s\" exceeds the maximum allowed upload size limit (20 MB) and could not be processed.",
                                fileName
                        ));
                    }
                    runningBytes += file.getSize();
                }
            }

            // 3. Extract text/vision content from uploaded files and enforce the 5,000-token context window cap
            List<ImageAttachment> imageAttachments = new ArrayList<>();
            StringBuilder fileContextBuilder = new StringBuilder();
            int accumulatedFileTokens = 0;

            if (files != null && !files.isEmpty()) {
                int filesCount = Math.min(files.size(), 3);
                for (int i = 0; i < filesCount; i++) {
                    MultipartFile file = files.get(i);
                    if (file == null || file.isEmpty()) continue;

                    int fileIndex = i + 1;
                    String rawName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                            ? file.getOriginalFilename()
                            : "attachment_" + fileIndex;
                    String lowerName = rawName.toLowerCase();
                    String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

                    byte[] fileBytes = file.getBytes();

                    MimeType detectedImageMime = detectImageMimeType(fileBytes, lowerName, contentType);
                    if (detectedImageMime != null) {
                        ImageAttachment optimizedImg = prepareImageForVision(fileBytes, rawName, detectedImageMime, imageAttachments.size() + 1);
                        imageAttachments.add(optimizedImg);
                        accumulatedFileTokens += 250; // Approximate vision token cost
                        fileContextBuilder.append(String.format(
                                "\n[FILE #%d - IMAGE: \"%s\"] (Attached as Vision Image #%d)\n",
                                fileIndex, rawName, imageAttachments.size()
                        ));
                        continue;
                    }

                    Resource fileResource = new ByteArrayResource(fileBytes) {
                        @Override
                        public String getFilename() {
                            return rawName;
                        }
                    };

                    String rawExtractedText;
                    if (lowerName.endsWith(".txt")
                            || lowerName.endsWith(".md")
                            || lowerName.endsWith(".csv")
                            || lowerName.endsWith(".json")
                            || lowerName.endsWith(".xml")
                            || lowerName.endsWith(".html")
                            || lowerName.endsWith(".log")
                            || contentType.startsWith("text/")) {
                        rawExtractedText = new String(fileBytes, StandardCharsets.UTF_8).trim();
                    } else {
                        rawExtractedText = extractRawDocumentText(fileResource, lowerName, contentType);
                    }

                    int docTokens = estimateTokens(rawExtractedText);
                    if (docTokens > MAX_FILE_CONTEXT_TOKENS || (userPromptTokens + accumulatedFileTokens + docTokens) > MAX_CONTEXT_TOKENS) {
                        return Flux.just(String.format(
                                "File too long: The content of \"%s\" (~%d tokens) exceeds the maximum 5,000-token context window. Please upload a shorter document or relevant excerpt.",
                                rawName, docTokens
                        ));
                    }

                    accumulatedFileTokens += docTokens;
                    fileContextBuilder.append(String.format(
                            "\n[FILE #%d - DOCUMENT: \"%s\"]\n%s\n[END OF FILE #%d]\n",
                            fileIndex, rawName, rawExtractedText, fileIndex
                    ));
                }
            }

            // Update session title if still default
            if (session.getTitle() == null || session.getTitle().equalsIgnoreCase("New Chat")) {
                String cleanTitleSource = safeUserText.replaceAll("\\s+", " ").trim();
                String newTitle = cleanTitleSource.length() > 32
                        ? cleanTitleSource.substring(0, 32) + "..."
                        : cleanTitleSource;
                session.setTitle(newTitle);
                session = sessionRepository.save(session);
            }

            // Fetch previous session messages BEFORE saving the incoming user message
            List<ChatMessage> previousMessages = messageRepository.findBySessionIdOrderByIdAsc(sessionId);

            // Persist the incoming user message with username and token count
            ChatMessage userMessageEntity = new ChatMessage();
            userMessageEntity.setSession(session);
            userMessageEntity.setUsername(username);
            String savedUserText = safeUserText;
            if (files != null && !files.isEmpty()) {
                savedUserText += " [Attached " + Math.min(files.size(), 3) + " File(s)]";
            }
            userMessageEntity.setText(savedUserText);
            userMessageEntity.setSender("USER");
            userMessageEntity.setTokensUsed((long) (userPromptTokens + accumulatedFileTokens));
            userMessageEntity.setCreatedAt(Instant.now().toString());
            messageRepository.save(userMessageEntity);

            // Build token-efficient context (User Identity + Policy RAG when relevant + Uploaded File Content)
            StringBuilder contextBuilder = new StringBuilder();
            String cleanDisplayName = username.startsWith("guest_") ? "Guest" : username;
            contextBuilder.append("USER IDENTITY: You are speaking with \"").append(cleanDisplayName)
                    .append("\" (username: ").append(username).append(").\n");

            // Retrieve policy chunks from Chatbot_Privacy_Policy.pdf when the query relates to policy, privacy, data, limits, or creator
            int ragTokens = 0;
            try {
                if (vectorStore != null) {
                    String lowerQuery = safeUserText.toLowerCase();
                    boolean isPolicyRelated = lowerQuery.contains("policy")
                            || lowerQuery.contains("privacy")
                            || lowerQuery.contains("terms")
                            || lowerQuery.contains("rule")
                            || lowerQuery.contains("data")
                            || lowerQuery.contains("limit")
                            || lowerQuery.contains("quota")
                            || lowerQuery.contains("delete")
                            || lowerQuery.contains("account")
                            || lowerQuery.contains("security")
                            || lowerQuery.contains("password")
                            || lowerQuery.contains("chatbot")
                            || lowerQuery.contains("rajnish");

                    if (isPolicyRelated) {
                        String effectiveSearchQuery = "ChatBot Privacy Policy Usage Policy data collection protection security user rights quota " + safeUserText;
                        List<Document> similarDocuments = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                        .query(effectiveSearchQuery)
                                        .topK(2)
                                        .similarityThreshold(0.25)
                                        .build()
                        );

                        if (similarDocuments != null && !similarDocuments.isEmpty()) {
                            String vectorContext = similarDocuments.stream()
                                    .map(Document::getText)
                                    .collect(Collectors.joining("\n---\n"));
                            ragTokens = estimateTokens(vectorContext);
                            contextBuilder.append("\nINTERNAL POLICY REFERENCE (Chatbot_Privacy_Policy.pdf):\n")
                                    .append(vectorContext)
                                    .append("\n");
                        }
                    }
                }
            } catch (Throwable e) {
                log.warn("Vector search skipped: {}", e.getMessage());
            }

            if (!fileContextBuilder.isEmpty()) {
                contextBuilder.append("\nATTACHED FILES CONTENT:\n").append(fileContextBuilder);
            }

            // Build a compact sliding window of recent messages (sent ONCE via .messages() to minimize token consumption)
            int remainingHistoryBudget = Math.min(
                    MAX_HISTORY_TOKENS,
                    Math.max(200, MAX_CONTEXT_TOKENS - (userPromptTokens + accumulatedFileTokens + ragTokens + 350))
            );

            List<Message> conversationHistoryMessages = new ArrayList<>();
            if (!previousMessages.isEmpty()) {
                List<Message> reversedBuffer = new ArrayList<>();
                int usedHistoryTokens = 0;
                int startIdx = previousMessages.size() - 1;
                int minIdx = Math.max(0, previousMessages.size() - 8);

                for (int i = startIdx; i >= minIdx; i--) {
                    ChatMessage msg = previousMessages.get(i);
                    String rawMsgText = msg.getText() != null ? msg.getText() : "";
                    if (rawMsgText.length() > 800) {
                        rawMsgText = rawMsgText.substring(0, 800) + "...";
                    }
                    int msgTokens = estimateTokens(rawMsgText);
                    if (usedHistoryTokens + msgTokens > remainingHistoryBudget) {
                        break;
                    }
                    usedHistoryTokens += msgTokens;
                    if ("USER".equalsIgnoreCase(msg.getSender())) {
                        reversedBuffer.add(new UserMessage(sanitizeTemplateBraces(rawMsgText)));
                    } else {
                        reversedBuffer.add(new AssistantMessage(sanitizeTemplateBraces(rawMsgText)));
                    }
                }
                Collections.reverse(reversedBuffer);
                conversationHistoryMessages.addAll(reversedBuffer);
            }

            String safeContext = sanitizeTemplateBraces(contextBuilder.toString().trim());
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
                    renderedSystemPrompt = "You are a professional AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + safeContext;
                }
            } catch (Throwable e) {
                log.error("Failed to render prompt template: {}", e.getMessage());
                renderedSystemPrompt = "You are a professional AI assistant developed by Rajnish Chauhan (https://rajnishsystems.in). Context:\n" + safeContext;
            }

            final String finalSysPrompt = sanitizeTemplateBraces(renderedSystemPrompt);

            StringBuilder userPromptBuilder = new StringBuilder();
            if (!imageAttachments.isEmpty()) {
                userPromptBuilder.append("Attached Vision Images in order:\n");
                for (int i = 0; i < imageAttachments.size(); i++) {
                    userPromptBuilder.append("- Image #").append(i + 1).append(": \"").append(imageAttachments.get(i).filename()).append("\"\n");
                }
            }
            userPromptBuilder.append(safeQueryForTemplate);
            final String finalUserPromptText = userPromptBuilder.toString();

            final int totalInputTokens = estimateTokens(finalSysPrompt)
                    + estimateTokens(finalUserPromptText)
                    + accumulatedFileTokens
                    + conversationHistoryMessages.stream().mapToInt(m -> estimateTokens(m.getText())).sum();

            // Final guard ensuring total context window never exceeds 5,000 tokens
            if (totalInputTokens > MAX_CONTEXT_TOKENS) {
                return Flux.just("Message or file too long: The combined request exceeds the 5,000-token context window limit. Please shorten your prompt or attach a smaller document.");
            }

            StringBuilder fullBotResponse = new StringBuilder();
            AtomicBoolean responsePersisted = new AtomicBoolean(false);
            final ChatSession targetSessionRef = session;

            var promptSpec = chatClient.prompt()
                    .system(finalSysPrompt)
                    .messages(conversationHistoryMessages);

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
                    .doFinally(signalType -> {
                        if (responsePersisted.compareAndSet(false, true) && !fullBotResponse.isEmpty()) {
                            try {
                                long outputTokens = Math.max(1L, estimateTokens(fullBotResponse.toString()));
                                long totalTokensSpent = Math.max(1L, totalInputTokens + outputTokens);

                                ChatMessage botMessage = new ChatMessage();
                                botMessage.setSession(targetSessionRef);
                                botMessage.setUsername(username);
                                botMessage.setText(fullBotResponse.toString());
                                botMessage.setSender("BOT");
                                botMessage.setTokensUsed(outputTokens);
                                botMessage.setCreatedAt(Instant.now().toString());
                                messageRepository.save(botMessage);

                                User userToUpdate = userRepository.findByUsername(username).orElse(null);
                                if (userToUpdate != null) {
                                    userToUpdate.setTokensUsed(userToUpdate.getTokensUsed() + totalTokensSpent);
                                    userToUpdate.setApiCallsCount(userToUpdate.getApiCallsCount() + 1);
                                    userRepository.save(userToUpdate);

                                    log.info("DB Token Audit | User: {} | Session: {} | Input: {} | Output: {} | Total Cumulative Tokens: {} | API Calls: {}",
                                            username, sessionId, totalInputTokens, outputTokens, userToUpdate.getTokensUsed(), userToUpdate.getApiCallsCount());
                                }
                            } catch (Throwable e) {
                                log.error("Failed to persist bot reply and token metrics for session {}: ", sessionId, e);
                            }
                        }
                    });
        } catch (Throwable fatalEx) {
            log.error("Fatal error in processAndStreamMessage: ", fatalEx);
            return Flux.just("An unexpected error occurred while processing your message: "
                    + (fatalEx.getMessage() != null ? fatalEx.getMessage() : "Please try again."));
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }

    private ImageAttachment prepareImageForVision(byte[] rawBytes, String rawName, MimeType detectedMime, int index) {
        String safeResourceName = "image_" + index;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original != null) {
                int maxDim = 1024;
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
                        param.setCompressionQuality(0.80f);
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

    private String extractRawDocumentText(Resource fileResource, String lowerName, String contentType) {
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

        return extracted.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String sanitizeTemplateBraces(String input) {
        if (input == null) return "";
        return input.replace("{", "(").replace("}", ")");
    }
}