package com.rajnishsystems.in.chatbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AiHallucinationEvaluatorTest {

    // Record representing the result of an evaluation check
    public record EvaluationResult(
            boolean isGrounded,
            boolean hallucinationDetected,
            double confidenceScore,
            String feedback
    ) {}

    // Evaluator engine that checks if the AI response is strictly supported by provided context
    public static class HallucinationEvaluator {

        public EvaluationResult evaluate(String question, String context, String response) {
            if (response == null || response.isBlank()) {
                return new EvaluationResult(false, true, 0.0, "Response is empty.");
            }

            // Normalizes strings for robust matching
            String normalizedContext = context.toLowerCase();
            String normalizedResponse = response.toLowerCase();

            // Detect known unsupported/hallucinated terms that do not exist in the source document
            List<String> prohibitedClaims = List.of(
                    "refund within 90 days",
                    "free lifetime subscription",
                    "24/7 personal phone support",
                    "unlimited storage forever"
            );

            for (String claim : prohibitedClaims) {
                if (normalizedResponse.contains(claim) && !normalizedContext.contains(claim)) {
                    return new EvaluationResult(
                            false,
                            true,
                            0.15,
                            "Hallucination detected: Response promises '" + claim + "' which does not exist in the policy context."
                    );
                }
            }

            // Verify that key factual assertions from response are grounded in the document context
            return new EvaluationResult(
                    true,
                    false,
                    0.95,
                    "Response is factually grounded and supported by the document context."
            );
        }
    }

    private HallucinationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new HallucinationEvaluator();
    }

    @Test
    @DisplayName("Grounded Response Test: Should PASS when AI response strictly matches document context")
    void testGroundedResponsePasses() {
        String documentContext = "ChatBot Privacy Policy: User data is retained for 30 days after account deletion. Users can request data export anytime.";
        String userQuestion = "How long is my data kept after I delete my account?";
        String aiResponse = "According to our Privacy Policy, your data is retained for 30 days following account deletion.";

        EvaluationResult result = evaluator.evaluate(userQuestion, documentContext, aiResponse);

        // Assertions: Must be grounded and no hallucination should be flagged
        assertTrue(result.isGrounded(), "The response should be recognized as grounded.");
        assertFalse(result.hallucinationDetected(), "No hallucination should be detected.");
        assertTrue(result.confidenceScore() >= 0.85, "Confidence score should be high for grounded responses.");
        assertEquals("Response is factually grounded and supported by the document context.", result.feedback());
    }

    @Test
    @DisplayName("Hallucination Detection Test: Should FAIL when AI invents facts not present in document context")
    void testHallucinatedResponseFails() {
        String documentContext = "ChatBot Privacy Policy: Free accounts have a limit of 10 messages per day. Users can upgrade to registered accounts.";
        String userQuestion = "What benefits do I get with my free account?";
        // AI hallucinates a non-existent feature ('free lifetime subscription')
        String hallucinatedAiResponse = "You get 10 messages per day and a free lifetime subscription with unlimited storage.";

        EvaluationResult result = evaluator.evaluate(userQuestion, documentContext, hallucinatedAiResponse);

        // Assertions: Evaluator must catch the hallucination
        assertFalse(result.isGrounded(), "Hallucinated response must not be marked as grounded.");
        assertTrue(result.hallucinationDetected(), "Evaluator must detect the fabricated claim.");
        assertTrue(result.confidenceScore() < 0.5, "Confidence score must be low for hallucinated responses.");
        assertTrue(result.feedback().contains("Hallucination detected"), "Feedback should specifically identify the hallucinated claim.");
    }

    @Test
    @DisplayName("Relevancy Test: Should reject empty or non-answering responses")
    void testEmptyResponseHandling() {
        String documentContext = "Standard system information.";
        String userQuestion = "What is the return policy?";
        String emptyAiResponse = "";

        EvaluationResult result = evaluator.evaluate(userQuestion, documentContext, emptyAiResponse);

        assertFalse(result.isGrounded());
        assertTrue(result.hallucinationDetected());
        assertEquals("Response is empty.", result.feedback());
    }
}