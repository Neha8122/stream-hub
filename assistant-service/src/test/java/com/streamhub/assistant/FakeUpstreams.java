package com.streamhub.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Claude Messages API and a fake catalog search on one port. Claude's
 * reply (the tool input), status and delay are set per test; every request
 * it receives is kept, headers and body, so tests can check what was sent.
 */
final class FakeUpstreams {

    record Request(String apiKey, String version, String body) { }

    static volatile int claudeStatus = 200;
    static volatile int claudeDelayMs;
    static volatile String toolInput = answer(true, "Try Title 2: a crew adrift in space.", 2);
    static final List<Request> claudeRequests = new CopyOnWriteArrayList<>();
    static volatile int catalogStatus = 200;
    static final AtomicInteger searches = new AtomicInteger();
    static final HttpServer server = start();

    static String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    static void reset() {
        claudeStatus = 200;
        claudeDelayMs = 0;
        toolInput = answer(true, "Try Title 2: a crew adrift in space.", 2);
        claudeRequests.clear();
        catalogStatus = 200;
        searches.set(0);
    }

    static String answer(boolean onTopic, String text, long... ids) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            b.append(i > 0 ? "," : "").append(ids[i]);
        }
        return "{\"on_topic\":" + onTopic + ",\"answer\":\"" + text + "\",\"title_ids\":" + b + "]}";
    }

    private FakeUpstreams() { }

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/v1/messages", FakeUpstreams::claude);
            s.createContext("/search", FakeUpstreams::search);
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void claude(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        claudeRequests.add(new Request(ex.getRequestHeaders().getFirst("x-api-key"),
                ex.getRequestHeaders().getFirst("anthropic-version"), body));
        try {
            if (claudeDelayMs > 0) {
                Thread.sleep(claudeDelayMs);
            }
            send(ex, claudeStatus, claudeStatus != 200 ? "{\"type\":\"error\"}" : """
                    {"id":"msg_1","type":"message","role":"assistant","model":"fake",
                     "content":[{"type":"tool_use","id":"toolu_1","name":"answer","input":%s}],
                     "stop_reason":"tool_use","usage":{"input_tokens":420,"output_tokens":55}}""".formatted(toolInput));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGaveUp) {
            // the assistant timed out and hung up: expected in the slow test
        } finally {
            ex.close();
        }
    }

    private static void search(HttpExchange ex) throws IOException {
        searches.incrementAndGet();
        StringBuilder hits = new StringBuilder();
        for (int id = 1; id <= 6; id++) {
            hits.append(id > 1 ? "," : "").append("{\"title\":{\"id\":").append(id).append(",\"name\":\"Title ")
                    .append(id).append("\",\"genres\":[\"sci-fi\"],\"releaseYear\":2020,\"durationMinutes\":100,")
                    .append("\"description\":\"A crew <b>adrift</b> in space.\"},\"keywordRank\":1,\"semanticRank\":1,")
                    .append("\"score\":0.03}");
        }
        send(ex, catalogStatus, "{\"query\":\"q\",\"mode\":\"HYBRID\",\"hits\":[" + hits + "]}");
        ex.close();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
