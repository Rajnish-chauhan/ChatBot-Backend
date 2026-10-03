package com.rajnishsystems.in.chatbot.tool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class TavilySearchTool {

    private final RestClient restClient;
    private final String tavilyApiKey;

    public TavilySearchTool() {
        this.tavilyApiKey = System.getenv("TAVILY_API_KEY");
        if (this.tavilyApiKey == null || this.tavilyApiKey.isBlank()) {
            throw new IllegalStateException("Environment variable 'TAVILY_API_KEY' is missing or not set.");
        }
        this.restClient = RestClient.create("https://api.tavily.com");
    }

    @Tool(description = "Search the live web using Tavily for up-to-date facts, news, and external information.")
    public String searchWeb(String query) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", this.tavilyApiKey,
                    "query", query,
                    "search_depth", "basic",
                    "include_answer", true
            );

            Map<String, Object> response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            return response != null ? response.toString() : "No search results returned from Tavily.";
        } catch (Exception ex) {
            return "Failed to fetch Tavily web search results: " + ex.getMessage();
        }
    }
}