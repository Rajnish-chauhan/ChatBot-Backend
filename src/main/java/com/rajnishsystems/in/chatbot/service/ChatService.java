package com.rajnishsystems.in.chatbot.service;
import org.springframework.ai.vectorstore.VectorStore;
import com.rajnishsystems.in.chatbot.tool.TavilySearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Value("classpath:/prompts/rag-prompt.st")
    private Resource ragPromptResource;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, TavilySearchTool tavilySearchTool) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder
                .defaultTools(tavilySearchTool)
                .build();
    }

    public String processRequest(String message, MultipartFile file) throws Exception {
        // 1. Retrieve vector documents
        List<Document> similarDocuments = vectorStore.similaritySearch(message);
        String context = similarDocuments.isEmpty()
                ? "No internal knowledge base documents found."
                : similarDocuments.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));

        // 2. Render prompt from StringTemplate
        PromptTemplate promptTemplate = new PromptTemplate(ragPromptResource);
        promptTemplate.add("context", context);
        promptTemplate.add("question", message);
        String finalPrompt = promptTemplate.render();

        // 3. Execute Multimodal call cleanly (No UserMessage or List.of() needed)
        return chatClient.prompt()
                .user(u -> {
                    u.text(finalPrompt);
                    if (file != null && !file.isEmpty()) {
                        String contentType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
                        // file.getResource() is safe and doesn't throw IOExceptions inside the lambda
                        u.media(new Media(MimeTypeUtils.parseMimeType(contentType), file.getResource()));
                    }
                })
                .call()
                .content();
    }
}