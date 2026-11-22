package dev.codeduel.judge;

import dev.codeduel.shared.Protocol.Status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class DockerSandbox {
    private static final int OUTPUT_LIMIT = 24_576;
    private static final System.Logger LOG = System.getLogger(DockerSandbox.class.getName());
    private final String image;
    private final String runtime;
    private final Duration timeout;

    record Result(Status status, String output) {}
    private record Capture(String text, boolean truncated) {}

    DockerSandbox(String image, String runtime, Duration timeout) {
        if (!image.matches("[a-zA-Z0-9][a-zA-Z0-9./_:@-]*") || !runtime.matches("[a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Imagen o runtime Docker inválidos");
        }
        this.image = image;
        this.runtime = runtime;
        this.timeout = timeout;
    }

    Result run(String source, String harness) throws InterruptedException {
        String name = "codeduel-case-" + UUID.randomUUID();
        List<String> command = new java.util.ArrayList<>(List.of(
                "docker", "run", "--rm", "--pull=never", "--name", name, "--network", "none",
                "--log-driver", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "48", "--memory", "256m", "--memory-swap", "256m", "--cpus", "1",
                "--ulimit", "nofile=128:128", "--ulimit", "core=0", "--user", "65534:65534",
                "--tmpfs", "/work:rw,noexec,nosuid,nodev,size=32m,mode=1777",
                "--hostname", "codeduel-sandbox", "--env", "HOME=/work",
                "--env", "HTTP_PROXY=", "--env", "HTTPS_PROXY=", "--env", "ALL_PROXY=", "--env", "NO_PROXY=",
                "--env", "http_proxy=", "--env", "https_proxy=", "--env", "all_proxy=", "--env", "no_proxy=", "-i"));
        if (!runtime.isEmpty()) command.addAll(List.of("--runtime", runtime));
        command.add(image);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Docker no está disponible", e);
            return new Result(Status.UNAVAILABLE, "");
        }
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var stdout = tasks.submit(() -> capture(process.getInputStream()));
                var stderr = tasks.submit(() -> capture(process.getErrorStream()));
                tasks.submit(() -> {
                    try (var stdin = process.getOutputStream()) {
                        stdin.write((encoded(source) + "\n" + encoded(harness) + "\n").getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                        // Docker may exit before reading the request.
                    }
                });
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return new Result(Status.TIME_LIMIT, "");
                }
                Capture output = stdout.get(1, TimeUnit.SECONDS);
                Capture diagnostics = stderr.get(1, TimeUnit.SECONDS);
                return switch (process.exitValue()) {
                    case 0 -> output.truncated() ? new Result(Status.RUNTIME_ERROR, "") : result(output.text());
                    case 10 -> new Result(Status.COMPILE_ERROR, diagnostics.text());
                    case 12 -> new Result(Status.COMPILE_ERROR, "Declara la clase pública Solution y la firma exacta de solve.");
                    case 137 -> new Result(Status.TIME_LIMIT, "");
                    case 14, 125, 126, 127 -> {
                        LOG.log(System.Logger.Level.WARNING, "Docker rechazó un caso: " + diagnostics.text());
                        yield new Result(Status.UNAVAILABLE, "");
                    }
                    default -> new Result(Status.RUNTIME_ERROR, "");
                };
            } catch (ExecutionException | TimeoutException e) {
                return new Result(Status.UNAVAILABLE, "");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    remove(name);
                }
            }
        }
    }

    private static Result result(String output) {
        if (!output.endsWith("\n")) return new Result(Status.RUNTIME_ERROR, "");
        try {
            String decoded = new String(Base64.getDecoder().decode(output.substring(0, output.length() - 1)),
                    StandardCharsets.UTF_8);
            return new Result(Status.PASS, decoded);
        } catch (IllegalArgumentException e) {
            return new Result(Status.RUNTIME_ERROR, "");
        }
    }

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Capture capture(InputStream stream) throws IOException {
        try (stream; var buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            boolean truncated = false;
            for (int read; (read = stream.read(chunk)) != -1;) {
                int room = OUTPUT_LIMIT - buffer.size();
                buffer.write(chunk, 0, Math.min(read, room));
                truncated |= read > room;
            }
            return new Capture(buffer.toString(StandardCharsets.UTF_8), truncated);
        }
    }

    private static void remove(String name) {
        try {
            var cleanup = new ProcessBuilder("docker", "rm", "-f", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(4, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "No se pudo limpiar " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
