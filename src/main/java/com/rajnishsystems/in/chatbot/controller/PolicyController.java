package com.rajnishsystems.in.chatbot.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class PolicyController {

    private final VectorStore vectorStore;

    @Autowired
    public PolicyController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // 1. Endpoint for humans to view or download the PDF
    @GetMapping("/privacy-policy")
    public ResponseEntity<Resource> getPrivacyPolicy() {
        Resource pdf = new ClassPathResource("Chatbot_Privacy_Policy.pdf");
        if (!pdf.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Privacy policy file not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Chatbot_Privacy_Policy.pdf\"")
                .body(pdf);
    }

    // 2. Endpoint to load the PDF text into the AI's Vector Database
    @GetMapping("/api/rag/load-pdf")
    public ResponseEntity<String> forceLoadPdf() {
        try {
            Resource pdfResource = new ClassPathResource("Chatbot_Privacy_Policy.pdf");
            if (!pdfResource.exists()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Error: Chatbot_Privacy_Policy.pdf not found in resources folder.");
            }

            // Step A: Read the PDF file
            PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);

            // Step B: Split the document into smaller semantic chunks for the AI using the updated builder
            TokenTextSplitter splitter = TokenTextSplitter.builder().build();
            List<Document> docs = splitter.apply(reader.get());

            // Step C: Convert to OpenAI embeddings and save to PostgreSQL
            vectorStore.add(docs);

            return ResponseEntity.ok("Success! Embedded and loaded " + docs.size() + " chunks from the PDF into the Vector Database.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error reading PDF: " + e.getMessage());
        }
    }
}