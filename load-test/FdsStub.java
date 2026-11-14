import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 부하 측정용 FDS 대역. POST /fds/evaluate 에 정해진 지연 뒤 항상 APPROVE로 답한다.
 *
 * 실행: java load-test/FdsStub.java <port> <delayMillis>
 *   예) java load-test/FdsStub.java 18090 100
 *
 * 지연을 ibank의 응답 타임아웃(기본 300ms)보다 길게 주면 FDS 장애 상황이 된다.
 */
public class FdsStub {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18090;
        long delay = args.length > 1 ? Long.parseLong(args[1]) : 0;
        byte[] body = """
                {"decision":"APPROVE","riskLevel":"NORMAL","reasonCodes":[],"decisionId":"stub"}"""
                .getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/fds/evaluate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 호출한 쪽이 타임아웃으로 먼저 끊었다
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("FDS stub listening on " + port + ", delay " + delay + "ms");
    }
}
