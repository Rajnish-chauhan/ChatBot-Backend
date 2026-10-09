package com.rajnishsystems.in.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiLiveEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // Helper to send prompt to OpenAI chat completions endpoint
    private String askOpenAi(String apiKey, String prompt) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.0
        );

        String rawResponse = restClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(rawResponse);
        return root.path("choices").get(0).path("message").path("content").asText().trim();
    }

    @Test
    @DisplayName("End-to-End Test: Ask any custom question, get AI answer, and evaluate hallucination")
    void testCustomMessageAndEvaluate() throws Exception {
        // Reads directly from the test Environment Variable OPENAI_API_KEY
        String apiKey = System.getenv("OPENAI_API_KEY");

        // Fail clearly if the environment variable is not passed
        assertNotNull(apiKey, "OPENAI_API_KEY environment variable is not set in your Run Configuration!");
        assertFalse(apiKey.isBlank(), "OPENAI_API_KEY environment variable cannot be blank!");

        // =========================================================================
        // 1. You can customize your document context and question here:
        // =========================================================================
        String knowledgeContext = """
                Welcome to Rajnish Systems ChatBot.
                - Free accounts can send up to 10 messages per day.
                - Registered users with verified email have a limit of 100 messages per day.
                - Account deletion permanently deletes all chat sessions and history immediately.
                - We do not offer telephone customer support; support is via email only.
                """;

        String userQuestion = "Can I call customer support on phone for help?";

        // =========================================================================
        // Step 1: AI generates response based on knowledge context
        // =========================================================================
        String promptForBot = """
                You are a helpful assistant. Answer the user question strictly using the provided context.
                If the answer is not mentioned, state that it is not available.
                
                [CONTEXT]:
                %s
                
                [QUESTION]:
                %s
                """.formatted(knowledgeContext, userQuestion);

        String botAnswer = askOpenAi(apiKey, promptForBot);

        System.out.println("====================================================");
        System.out.println("USER QUESTION: " + userQuestion);
        System.out.println("AI GENERATED ANSWER: " + botAnswer);
        System.out.println("====================================================");

        assertNotNull(botAnswer);

        // =========================================================================
        // Step 2: Evaluator judge checks if AI answer contains hallucination
        // =========================================================================
        String judgePrompt = """
                You are a strict QA evaluator assessing AI hallucination.
                
                [DOCUMENT CONTEXT]:
                %s
                
                [USER QUESTION]:
                %s
                
                [AI ANSWER]:
                %s
                
                TASK:
                Analyze whether the AI ANSWER is completely truthful and backed by the DOCUMENT CONTEXT.
                Format your output in this format:
                VERDICT: [GROUNDED or HALLUCINATED]
                REASON: [Short 1-sentence reason]
                """.formatted(knowledgeContext, userQuestion, botAnswer);

        String judgeVerdict = askOpenAi(apiKey, judgePrompt);

        System.out.println("====== EVALUATOR JUDGE REPORT ======");
        System.out.println(judgeVerdict);
        System.out.println("====================================");

        // Assert that the generated answer is grounded in facts
        assertTrue(judgeVerdict.contains("GROUNDED"), "The AI response should be grounded in the provided context");
    }
}