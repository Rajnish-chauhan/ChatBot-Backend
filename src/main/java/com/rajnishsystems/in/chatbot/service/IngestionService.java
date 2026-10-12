package com.rajnishsystems.in.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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

    // Server start hote hi PDF Tika ke through automatically vector db me load ho jayegi
    @EventListener(ApplicationReadyEvent.class)
    public void loadPrivacyPdfOnStartup() {
        try {
            Resource pdfResource = new ClassPathResource("Chatbot_Privacy_Policy.pdf");
            if (pdfResource.exists()) {
                ingestDocument(pdfResource);
                log.info("RAG Success: Chatbot_Privacy_Policy.pdf loaded via Tika into Vector DB.");
            } else {
                log.warn("Chatbot_Privacy_Policy.pdf resources folder me nahi mili.");
            }
        } catch (Exception e) {
            log.error("Failed to load PDF via Tika: ", e);
        }
    }

    public void ingestDocument(Resource documentResource) {
        // 1. Read PDF document using Apache Tika
        TikaDocumentReader reader = new TikaDocumentReader(documentResource);
        List<Document> rawDocuments = reader.get();

        // 2. Chunking: split into chunks
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(600)
                .withMinChunkSizeChars(200)
                .build();

        List<Document> chunks = splitter.apply(rawDocuments);

        // 3. Generate embeddings & store in PostgreSQL Vector DB
        vectorStore.add(chunks);
    }
}