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

    // Define the tool as a standard inner class
    public class TavilyWebSearcher {

        // This is the annotation Spring AI is complaining about missing!
        @Tool(description = "Search the web for real-time, current events, or up-to-date information. Call this ONLY if the answer is not found in the provided internal PDF CONTEXT.")
        public String search(String query) {
            System.out.println("====== AI TRIGGERED REAL-TIME WEB SEARCH ======");
            System.out.println("Searching for: " + query);

            RestClient restClient = RestClient.create();

            try {
                Map response = restClient.post()
                        .uri("https://api.tavily.com/search")
                        .header("Content-Type", "application/json")
                        .body(Map.of(
                                "api_key", tavilyApiKey,
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

    // Register it as a Bean so ChatService can find it
    @Bean
    public TavilyWebSearcher tavilySearchTool() {
        return new TavilyWebSearcher();
    }
}