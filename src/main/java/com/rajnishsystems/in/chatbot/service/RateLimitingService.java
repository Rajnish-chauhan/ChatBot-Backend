package com.rajnishsystems.in.chatbot.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitingService {

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    public Bucket resolveBucket(String key) {
        return cache.computeIfAbsent(key, this::newBucket);
    }

    private Bucket newBucket(String key) {
        // Daily rate limit of 20 requests per 24 hours. Resets intervally after 24 hours.
        Bandwidth limit = Bandwidth.classic(20, Refill.intervally(20, Duration.ofDays(1)));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}