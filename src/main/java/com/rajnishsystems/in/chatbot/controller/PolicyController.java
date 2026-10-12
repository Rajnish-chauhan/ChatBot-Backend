package com.rajnishsystems.in.chatbot.controller;

import com.rajnishsystems.in.chatbot.service.IngestionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PolicyController {

    private final IngestionService ingestionService;

    public PolicyController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    // Agar kabhi manually dobara PDF load karni ho to is endpoint ko call kar sakte ho
    @GetMapping("/api/rag/load-pdf")
    public ResponseEntity<String> forceLoadPdf() {
        try {
            Resource pdfResource = new ClassPathResource("Chatbot_Privacy_Policy.pdf");
            if (!pdfResource.exists()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Error: Chatbot_Privacy_Policy.pdf not found in resources folder.");
            }

            ingestionService.ingestDocument(pdfResource);
            return ResponseEntity.ok("Success! PDF reloaded via Tika into Vector DB.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error reading PDF: " + e.getMessage());
        }
    }
}