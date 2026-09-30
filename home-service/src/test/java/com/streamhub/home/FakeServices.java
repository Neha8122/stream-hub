package com.streamhub.home;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake history and catalog services on one port, shared by every home test
 * class. Started once and left running until the JVM exits: test classes
 * run one after another in the same JVM, and one stopping it would break
 * the next (which is exactly what happened once).
 */
final class FakeServices {

    /** How the fake behaves for each service. */
    static final class Behaviour {
        volatile int delayMs;
        volatile int status = 200;
        final AtomicInteger failNext = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        /** The traceparent header of every request, in arrival order. */
        final java.util.List<String> traceparents = new java.util.concurrent.CopyOnWriteArrayList<>();

        void reset() {
            delayMs = 0;
            status = 200;
            failNext.set(0);
            requests.set(0);
            inFlight.set(0);
            maxInFlight.set(0);
            traceparents.clear();
        }
    }

    static final Behaviour history = new Behaviour();
    static final Behaviour catalog = new Behaviour();
    static final Behaviour recs = new Behaviour();
    static final HttpServer server = start();

    static String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    static void reset() {
        history.reset();
        catalog.reset();
        recs.reset();
    }

    private FakeServices() { }

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/history/continue-watching", ex -> serve(ex, history,
                    "[{\"titleId\":5,\"positionSeconds\":100,\"durationSeconds\":3600,\"updatedAt\":\"2026-09-30T10:00:00Z\"}]"));
            s.createContext("/recs/for-you", ex -> serve(ex, recs,
                    "{\"source\":\"PERSONAL\",\"titles\":[" + title(42) + "]}"));
            s.createContext("/titles", ex -> {
                String body;
                if (ex.getRequestURI().getPath().equals("/titles/batch")) {
                    body = "[" + title(5) + "]";
                } else {
                    StringBuilder b = new StringBuilder("[");
                    for (int i = 1; i <= 10; i++) {
                        b.append(i > 1 ? "," : "").append(title(i));
                    }
                    body = b.append("]").toString();
                }
                serve(ex, catalog, body);
            });
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String title(long id) {
        return "{\"id\":" + id + ",\"name\":\"The Title " + id + "\",\"genres\":[\"drama\"],\"releaseYear\":2020,"
                + "\"durationMinutes\":100,\"description\":\"x\"}";
    }

    private static void serve(HttpExchange ex, Behaviour b, String body) throws IOException {
        b.requests.incrementAndGet();
        String tp = ex.getRequestHeaders().getFirst("traceparent");
        b.traceparents.add(tp == null ? "-" : tp);
        int now = b.inFlight.incrementAndGet();
        b.maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (b.delayMs > 0) {
                Thread.sleep(b.delayMs);
            }
            int status = b.failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : b.status;
            byte[] bytes = (status == 200 ? body : "{\"error\":\"boom\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGaveUp) {
            // the caller timed out and closed the connection: expected in these tests
        } finally {
            b.inFlight.decrementAndGet();
            ex.close();
        }
    }
}
