package kh.cmt.bff;

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
 * Test double for everything behind the BFF, on one local port:
 * <ul>
 *   <li>{@code /api/**}: stands in for cmt-api and echoes what it received as JSON</li>
 *   <li>{@code /oauth2/token}: stands in for the auth server's token endpoint (refresh); slow on purpose</li>
 *   <li>{@code /oauth2/revoke}: records revoke calls</li>
 * </ul>
 */
class StubBackend implements AutoCloseable {

    private final HttpServer server;
    final AtomicInteger tokenCalls = new AtomicInteger();
    final List<String> revokeCalls = new CopyOnWriteArrayList<>();
    final List<String> tokenRequestBodies = new CopyOnWriteArrayList<>();

    StubBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api", this::echo);
        server.createContext("/auth/password", this::echo);
        server.createContext("/oauth2/token", this::token);
        server.createContext("/oauth2/revoke", this::revoke);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void echo(HttpExchange exchange) throws IOException {
        String json = "{\"method\":\"" + exchange.getRequestMethod() + "\""
                + ",\"path\":\"" + exchange.getRequestURI().getPath() + "\""
                + ",\"authorization\":" + quote(exchange.getRequestHeaders().getFirst("Authorization"))
                + ",\"cookie\":" + quote(exchange.getRequestHeaders().getFirst("Cookie"))
                + ",\"xsrf\":" + quote(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN")) + "}";
        respond(exchange, 200, json);
    }

    private void token(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        tokenRequestBodies.add(body);
        int n = tokenCalls.incrementAndGet();
        try {
            Thread.sleep(300);   // long enough for parallel requests to overlap
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(exchange, 200, "{\"access_token\":\"new-access-" + n + "\",\"refresh_token\":\"new-refresh-" + n
                + "\",\"token_type\":\"Bearer\",\"expires_in\":900}");
    }

    private void revoke(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        revokeCalls.add(exchange.getRequestHeaders().getFirst("Authorization") + " | " + body);
        respond(exchange, 200, "");
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "'") + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
