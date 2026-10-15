package com.rajnishsystems.in.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // Automatically ingest the privacy policy PDF on startup if the vector store does not already contain it
    @EventListener(ApplicationReadyEvent.class)
    public void loadPrivacyPdfOnStartup() {
        try {
            Resource pdfResource = new ClassPathResource("Chatbot_Privacy_Policy.pdf");
            if (!pdfResource.exists()) {
                log.warn("Chatbot_Privacy_Policy.pdf not found in classpath resources.");
                return;
            }

            List<Document> existing = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("ChatBot Privacy Policy Rajnish Systems")
                            .topK(1)
                            .similarityThreshold(0.50)
                            .build()
            );

            if (existing == null || existing.isEmpty()) {
                ingestDocument(pdfResource);
                log.info("Chatbot_Privacy_Policy.pdf ingested into vector store via Apache Tika.");
            } else {
                log.info("Policy chunks already present in vector store; skipping duplicate startup ingestion.");
            }
        } catch (Exception e) {
            log.error("Failed to ingest policy PDF on startup: ", e);
        }
    }

    public void ingestDocument(Resource documentResource) {
        TikaDocumentReader reader = new TikaDocumentReader(documentResource);
        List<Document> rawDocuments = reader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(600)
                .withMinChunkSizeChars(200)
                .build();

        List<Document> chunks = splitter.apply(rawDocuments);
        vectorStore.add(chunks);
    }
}