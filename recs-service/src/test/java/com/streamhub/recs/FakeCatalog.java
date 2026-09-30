package com.streamhub.recs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A catalog with hand-made embeddings, so the right answer is known:
 * titles 1-4 are "space" (axis 0), 5-8 "romance" (axis 1), 9-12 "crime"
 * (axis 2). Each also has a small axis of its own so no two are identical.
 * Title 99 exists but isn't embedded yet.
 */
final class FakeCatalog {

    static final int DIMS = 384;
    static final AtomicInteger embeddingCalls = new AtomicInteger();
    static volatile boolean down;
    static final HttpServer server = start();
    private static final ObjectMapper json = new ObjectMapper();

    static String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    static String cluster(long id) {
        return id <= 4 ? "space" : id <= 8 ? "romance" : "crime";
    }

    static float[] vector(long id) {
        float[] v = new float[DIMS];
        v[id <= 4 ? 0 : id <= 8 ? 1 : 2] = 1f;
        v[10 + (int) id] = 0.2f;
        return v;
    }

    private FakeCatalog() { }

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/titles", FakeCatalog::handle);
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        if (down) {
            send(ex, 503, "{}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/embedding")) {
            embeddingCalls.incrementAndGet();
            long id = Long.parseLong(path.split("/")[2]);
            send(ex, id >= 1 && id <= 12 ? 200 : 404, id >= 1 && id <= 12 ? json.writeValueAsString(vector(id)) : "");
        } else if (path.equals("/titles/nearest")) {
            JsonNode req = json.readTree(ex.getRequestBody());
            float[] q = json.treeToValue(req.get("vector"), float[].class);
            Set<Long> exclude = new HashSet<>();
            req.get("exclude").forEach(n -> exclude.add(n.asLong()));
            List<Long> ids = new ArrayList<>();
            for (long id = 1; id <= 12; id++) {
                if (!exclude.contains(id)) {
                    ids.add(id);
                }
            }
            ids.sort(Comparator.comparingDouble((Long id) -> -cosine(q, vector(id))).thenComparing(id -> id));
            send(ex, 200, titles(ids.subList(0, Math.min(req.get("limit").asInt(), ids.size()))));
        } else if (path.equals("/titles/batch")) {
            List<Long> ids = new ArrayList<>();
            for (String p : ex.getRequestURI().getQuery().replace("ids=", "").split(",")) {
                ids.add(Long.parseLong(p));
            }
            send(ex, 200, titles(ids));
        } else {
            send(ex, 404, "");
        }
    }

    private static String titles(List<Long> ids) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            b.append(i > 0 ? "," : "").append("{\"id\":").append(id).append(",\"name\":\"Title ").append(id)
                    .append("\",\"genres\":[\"").append(cluster(id)).append("\"],\"releaseYear\":2020,")
                    .append("\"durationMinutes\":100,\"description\":\"x\"}");
        }
        return b.append("]").toString();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
