package com.rajnishsystems.in.chatbot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.client.RestClient;

import java.util.Map;

public class TavilyWebSearchTool {
// finds realtime data from web
    private final String apiKey;

    public TavilyWebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search the live web using Tavily for up-to-date facts, news, and external information. Call this ONLY if the answer is not found in the provided internal PDF CONTEXT.")
    public String searchWeb(String query) {
        System.out.println("====== AI TRIGGERED REAL-TIME WEB SEARCH ======");
        System.out.println("Searching for: " + query);

        try {
            Map response = RestClient.create().post()
                    .uri("https://api.tavily.com/search")
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", query,
                            "search_depth", "basic",
                            "include_answer", true
                    ))
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("answer")) {
                return response.get("answer").toString();
            }
            return "No relevant real-time information found.";

        } catch (Exception e) {
            System.err.println("Tavily search failed: " + e.getMessage());
            return "Web search is currently unavailable.";
        }
    }
}