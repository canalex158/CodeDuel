package dev.codeduel.judge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codeduel.shared.Catalog;
import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Job;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class JudgeMain {
    private static final System.Logger LOG = System.getLogger(JudgeMain.class.getName());
    private final Grader grader;
    private final byte[] auth;
    private final Semaphore capacity;

    private JudgeMain(Grader grader, String token, int parallelism) {
        this.grader = grader;
        auth = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        capacity = new Semaphore(parallelism);
    }

    public static void main(String[] args) throws IOException {
        String token = env("CODEDUEL_JUDGE_TOKEN", "");
        if (token.length() < 32 || token.length() > 256 || !token.matches("[!-~]+")) {
            throw new IllegalArgumentException("Configura CODEDUEL_JUDGE_TOKEN con 32–256 caracteres ASCII sin espacios");
        }
        String image = env("CODEDUEL_SANDBOX_IMAGE", "codeduel-sandbox:1.0.0");
        String runtime = env("CODEDUEL_SANDBOX_RUNTIME", "");
        var service = new JudgeMain(new Grader(new DockerSandbox(image, runtime, Duration.ofSeconds(8))), token,
                Integer.parseInt(env("CODEDUEL_JUDGE_PARALLELISM", "4")));
        var address = new InetSocketAddress(env("CODEDUEL_JUDGE_BIND", "127.0.0.1"),
                Integer.parseInt(env("CODEDUEL_JUDGE_PORT", "8091")));
        var server = HttpServer.create(address, 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/evaluate", service::handle);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdownNow();
        }));
        LOG.log(System.Logger.Level.INFO, "Evaluador CodeDuel en " + server.getAddress());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"/evaluate".equals(exchange.getRequestURI().getPath()) || !"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "{}");
                return;
            }
            String header = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).orElse("");
            if (!MessageDigest.isEqual(auth, header.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, "{}");
                return;
            }
            if (!capacity.tryAcquire()) {
                respond(exchange, 503, "{}");
                return;
            }
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(Protocol.MAX_MESSAGE_BYTES + 1);
                if (bytes.length > Protocol.MAX_MESSAGE_BYTES) {
                    respond(exchange, 413, "{}");
                    return;
                }
                Job job;
                try {
                    job = Protocol.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Job.class);
                } catch (com.google.gson.JsonParseException e) {
                    respond(exchange, 400, "{}");
                    return;
                }
                if (job == null || job.source() == null || job.source().isBlank()
                        || job.source().getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_SOURCE_BYTES
                        || !Catalog.contains(job.challengeId())) {
                    respond(exchange, 400, "{}");
                    return;
                }
                respond(exchange, 200, Protocol.JSON.toJson(grader.grade(job)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                capacity.release();
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String env(String name, String fallback) {
        return Optional.ofNullable(System.getenv(name)).orElse(fallback);
    }
}
