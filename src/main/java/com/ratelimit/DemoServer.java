package com.ratelimit;

import com.ratelimit.algorithm.FixedWindowRateLimiter;
import com.ratelimit.algorithm.LeakyBucketRateLimiter;
import com.ratelimit.algorithm.SlidingWindowLogRateLimiter;
import com.ratelimit.algorithm.TokenBucketRateLimiter;
import com.ratelimit.store.InMemoryStore;
import com.ratelimit.store.InMemorySlidingWindowStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class DemoServer {

    private static final String CLIENT_KEY = "demo-client";

    private static RateLimitConfig config = RateLimitConfig.of(10, Duration.ofSeconds(10));
    private static RateLimiter tokenBucket;
    private static RateLimiter leakyBucket;
    private static RateLimiter fixedWindow;
    private static RateLimiter slidingWindowLog;

    public static void main(String[] args) throws IOException {
        initLimiters();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/request", DemoServer::handleRequest);
        server.createContext("/api/status", DemoServer::handleStatus);
        server.createContext("/api/reset", DemoServer::handleReset);
        server.createContext("/api/config", DemoServer::handleConfig);
        server.createContext("/", DemoServer::serveUI);
        server.setExecutor(null);
        server.start();

        System.out.println("Demo server running at http://localhost:8080");
    }

    private static void initLimiters() {
        tokenBucket = new TokenBucketRateLimiter(config, new InMemoryStore());
        leakyBucket = new LeakyBucketRateLimiter(config, new InMemoryStore());
        fixedWindow = new FixedWindowRateLimiter(config, new InMemoryStore());
        slidingWindowLog = new SlidingWindowLogRateLimiter(config, new InMemorySlidingWindowStore());
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        RateLimitResult tokenResult = tokenBucket.tryAcquire(CLIENT_KEY);
        RateLimitResult leakyResult = leakyBucket.tryAcquire(CLIENT_KEY);
        RateLimitResult fixedResult = fixedWindow.tryAcquire(CLIENT_KEY);
        RateLimitResult slidingResult = slidingWindowLog.tryAcquire(CLIENT_KEY);

        long nowMs = System.currentTimeMillis();
        long windowMs = config.refillPeriod().toMillis();
        long fixedWindowStartMs = (nowMs / windowMs) * windowMs;

        String json = String.format("""
            {
              "serverTimeMs": %d,
              "windowMs": %d,
              "fixedWindowStartMs": %d,
              "tokenBucket": {"allowed": %b, "remaining": %d, "retryAfterMs": %d},
              "leakyBucket": {"allowed": %b, "remaining": %d, "retryAfterMs": %d},
              "fixedWindow": {"allowed": %b, "remaining": %d, "retryAfterMs": %d},
              "slidingWindowLog": {"allowed": %b, "remaining": %d, "retryAfterMs": %d}
            }""",
                nowMs, windowMs, fixedWindowStartMs,
                tokenResult.allowed(), tokenResult.remaining(), tokenResult.retryAfter().toMillis(),
                leakyResult.allowed(), leakyResult.remaining(), leakyResult.retryAfter().toMillis(),
                fixedResult.allowed(), fixedResult.remaining(), fixedResult.retryAfter().toMillis(),
                slidingResult.allowed(), slidingResult.remaining(), slidingResult.retryAfter().toMillis());

        sendJson(exchange, 200, json);
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        RateLimitResult tokenStatus = tokenBucket.peek(CLIENT_KEY);
        RateLimitResult leakyStatus = leakyBucket.peek(CLIENT_KEY);
        RateLimitResult fixedStatus = fixedWindow.peek(CLIENT_KEY);
        RateLimitResult slidingStatus = slidingWindowLog.peek(CLIENT_KEY);

        long nowMs = System.currentTimeMillis();
        long windowMsVal = config.refillPeriod().toMillis();
        long fixedWindowStartMs = (nowMs / windowMsVal) * windowMsVal;

        String json = String.format("""
            {
              "serverTimeMs": %d,
              "windowMs": %d,
              "fixedWindowStartMs": %d,
              "tokenBucket": {"remaining": %d},
              "leakyBucket": {"remaining": %d},
              "fixedWindow": {"remaining": %d},
              "slidingWindowLog": {"remaining": %d}
            }""",
                nowMs, windowMsVal, fixedWindowStartMs,
                tokenStatus.remaining(),
                leakyStatus.remaining(),
                fixedStatus.remaining(),
                slidingStatus.remaining());

        sendJson(exchange, 200, json);
    }

    private static void handleReset(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI());
        long cap = Long.parseLong(params.getOrDefault("capacity", "10"));
        long windowSec = Long.parseLong(params.getOrDefault("window", "10"));

        config = RateLimitConfig.of(cap, Duration.ofSeconds(windowSec));
        initLimiters();

        sendJson(exchange, 200, "{\"status\": \"reset\", \"capacity\": " + cap + ", \"windowSec\": " + windowSec + "}");
    }

    private static void handleConfig(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String json = String.format("{\"capacity\": %d, \"windowSec\": %d}",
                config.capacity(), config.refillPeriod().toSeconds());
        sendJson(exchange, 200, json);
    }

    private static void serveUI(HttpExchange exchange) throws IOException {
        try (var is = DemoServer.class.getResourceAsStream("/demo.html")) {
            if (is == null) {
                String msg = "demo.html not found on classpath. Place it in src/main/resources/";
                exchange.sendResponseHeaders(404, msg.length());
                exchange.getResponseBody().write(msg.getBytes());
                exchange.getResponseBody().close();
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], kv[1]);
        }
        return params;
    }
}
