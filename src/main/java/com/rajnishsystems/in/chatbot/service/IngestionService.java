package com.rajnishsystems.in.chatbot.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionService {

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestDocument(Resource documentResource) {
        // 1. Read document
        TikaDocumentReader reader = new TikaDocumentReader(documentResource);
        List<Document> rawDocuments = reader.get();

        // 2. Chunking: Use the new builder pattern
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .build();

        List<Document> chunks = splitter.apply(rawDocuments);

        // 3. Generate embeddings & store in PostgreSQL
        vectorStore.add(chunks);
    }
}