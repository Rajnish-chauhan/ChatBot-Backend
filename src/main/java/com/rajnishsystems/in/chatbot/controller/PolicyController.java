package com.rajnishsystems.in.chatbot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PolicyController {

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
}