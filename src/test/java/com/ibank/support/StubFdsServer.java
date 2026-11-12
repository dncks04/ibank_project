package com.ibank.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * FDS 서비스 흉내. JDK 내장 HTTP 서버로 {@code POST /fds/evaluate}에 테스트가 정한 응답을 돌려준다.
 * 응답 지연·오류 상태·깨진 본문을 만들어 타임아웃과 대체 정책을 검증하는 데 쓴다.
 */
public class StubFdsServer implements AutoCloseable {

    /** 받은 요청 한 건. */
    public record Received(String authorization, String body) {}

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = approveBody();
    private volatile long delayMillis = 0;

    public StubFdsServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/fds/evaluate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"), body));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 이미 끊었다
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return received;
    }

    /** 기본 상태(즉시 200 APPROVE)로 되돌리고 받은 요청 기록을 지운다. */
    public void reset() {
        received.clear();
        respond(200, approveBody());
        delayMillis = 0;
    }

    public void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public static String approveBody() {
        return """
                {"decision":"APPROVE","riskLevel":"NORMAL","reasonCodes":[],"decisionId":"dec-approve"}""";
    }

    public static String holdBody(String... reasonCodes) {
        String codes = String.join(",", java.util.Arrays.stream(reasonCodes).map(c -> "\"" + c + "\"").toList());
        return """
                {"decision":"HOLD","riskLevel":"DANGER","reasonCodes":[%s],"decisionId":"dec-hold"}""".formatted(codes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
