package dev.codeduel.server;

import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Grade;
import dev.codeduel.shared.Protocol.Job;
import dev.codeduel.shared.Protocol.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
final class JudgeGateway {
    private static final System.Logger LOG = System.getLogger(JudgeGateway.class.getName());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final String authorization;

    JudgeGateway(@Value("${codeduel.judge-url}") String endpoint, @Value("${codeduel.judge-token}") String token) {
        this.endpoint = URI.create(endpoint);
        if (token.length() < 32 || token.length() > 256 || !token.matches("[!-~]+")) {
            throw new IllegalArgumentException("Configura CODEDUEL_JUDGE_TOKEN en el servidor y el evaluador.");
        }
        authorization = "Bearer " + token;
    }

    Grade evaluate(Job job) {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Protocol.JSON.toJson(job))).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() == 200 && bytes.length <= Protocol.MAX_MESSAGE_BYTES) {
                Grade result = Protocol.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Grade.class);
                if (result != null && result.status() != null) return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Falló la solicitud al evaluador", e);
        }
        return unavailable();
    }

    static Grade unavailable() { return new Grade(Status.UNAVAILABLE, 0, 0, "Evaluador no disponible. Vuelve a intentarlo."); }
}
