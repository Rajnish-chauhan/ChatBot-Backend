package com.rajnishsystems.in.chatbot.config;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Configuration
public class AiToolsConfig {

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    // We changed the bean name here to "liveWebSearch" to avoid conflicts
    @Bean
    public TavilyWebSearcher liveWebSearch() {
        return new TavilyWebSearcher(tavilyApiKey);
    }

    public static class TavilyWebSearcher {
        private final String apiKey;

        public TavilyWebSearcher(String apiKey) {
            this.apiKey = apiKey;
        }

        @Tool(description = "Search the web for real-time, current events, or up-to-date information. Call this ONLY if the answer is not found in the provided internal PDF CONTEXT.")
        public String search(String query) {
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
}