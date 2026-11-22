package dev.codeduel.server;

import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Action;
import dev.codeduel.shared.Protocol.Event;
import dev.codeduel.shared.Protocol.Message;
import dev.codeduel.shared.Protocol.Phase;
import dev.codeduel.shared.Protocol.Request;
import dev.codeduel.shared.Protocol.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CODEDUEL_TEST_ENDPOINT", matches = "wss?://.+")
class WebSocketIntegrationTest {
    private static final URI ENDPOINT = URI.create(System.getenv().getOrDefault("CODEDUEL_TEST_ENDPOINT", "ws://127.0.0.1:8080/duel"));
    private static final String CORRECT = """
            public class Solution {
                public static int solve(int[] nums) {
                    return java.util.Arrays.stream(nums).sum();
                }
            }
            """;
    private static final String ONLY_PUBLIC = """
            public class Solution {
                public static int solve(int[] nums) {
                    return nums.length == 3 ? 6 : nums.length == 2 ? 3 : 0;
                }
            }
            """;

    @Test void twoRemoteClientsPlayWithPublicAndHiddenTestsThenRematch() throws Exception {
        try (var host = new Client(); var guest = new Client(); var third = new Client()) {
            assertEquals(15, host.await(Event.HELLO).hello().challenges().size());
            guest.await(Event.HELLO);
            third.await(Event.HELLO);

            third.send(request(Action.JOIN, "Tercero", "OOOOOOOO", null, null, null, null));
            assertEquals("ROOM_NOT_FOUND", third.await(Event.ERROR).error().code());
            host.send(request(Action.CREATE, "Alicia", null, null, null, null, null));
            var hostSession = host.await(Event.SESSION).session();
            String code = hostSession.code();
            guest.send(request(Action.JOIN, "Bruno", code, null, null, null, null));
            var guestSession = guest.await(Event.SESSION).session();
            host.await(message -> room(message, Phase.LOBBY) && message.room().players().size() == 2);
            third.send(request(Action.JOIN, "Tercero", code, null, null, null, null));
            assertEquals("ROOM_FULL", third.await(Event.ERROR).error().code());

            guest.send(Request.action(Action.START));
            assertEquals("CANNOT_START", guest.await(Event.ERROR).error().code());
            host.send(request(Action.SELECT, null, null, null, "maximum", null, null));
            guest.await(message -> room(message, Phase.LOBBY) && "maximum".equals(message.room().challengeId()));
            host.send(request(Action.SELECT, null, null, null, "sum", null, null));
            host.await(message -> room(message, Phase.LOBBY) && "sum".equals(message.room().challengeId()));
            host.send(Request.action(Action.START));
            var hostCountdown = host.await(message -> room(message, Phase.COUNTDOWN)).room();
            var guestCountdown = guest.await(message -> room(message, Phase.COUNTDOWN)).room();
            String round = hostCountdown.roundId();
            assertEquals(round, guestCountdown.roundId());
            assertEquals(hostCountdown.startsAt(), guestCountdown.startsAt());
            host.send(request(Action.SUBMIT, null, null, null, null, CORRECT, round));
            assertEquals("NOT_ACTIVE", host.await(Event.ERROR).error().code());
            host.await(message -> room(message, Phase.ACTIVE));
            guest.await(message -> room(message, Phase.ACTIVE));

            guest.disconnect();
            host.await(message -> room(message, Phase.ACTIVE)
                    && message.room().players().stream().anyMatch(player -> !player.connected()));
            try (var resumed = new Client()) {
                resumed.await(Event.HELLO);
                resumed.send(request(Action.RESUME, null, code, guestSession.token(), null, null, null));
                assertEquals(guestSession, resumed.await(Event.SESSION).session());
                var resumedRoom = resumed.await(message -> room(message, Phase.ACTIVE)).room();
                assertEquals(round, resumedRoom.roundId());

                host.send(request(Action.RUN, null, null, null, null, ONLY_PUBLIC, round));
                var dryRun = host.await(Event.EVALUATION).evaluation();
                assertFalse(dryRun.submission());
                assertEquals(Status.PASS, dryRun.grade().status(), dryRun.grade().detail());
                assertEquals(3, dryRun.grade().passedPublic());

                resumed.send(request(Action.SUBMIT, null, null, null, null, "public class Solution {", round));
                var compiler = resumed.await(Event.EVALUATION).evaluation().grade();
                assertEquals(Status.COMPILE_ERROR, compiler.status(), compiler.detail());

                host.send(request(Action.SUBMIT, null, null, null, null, ONLY_PUBLIC, round));
                var hidden = host.await(Event.EVALUATION).evaluation().grade();
                assertEquals(Status.WRONG_ANSWER, hidden.status(), hidden.detail());
                assertEquals(3, hidden.passedPublic());

                resumed.send(request(Action.SUBMIT, null, null, null, null, CORRECT, round));
                var accepted = resumed.await(Event.EVALUATION).evaluation().grade();
                assertEquals(Status.PASS, accepted.status(), accepted.detail());
                var result = resumed.await(message -> room(message, Phase.FINISHED)).room().outcome();
                assertEquals(guestSession.playerId(), result.winnerId());
                assertEquals("Bruno", result.winnerName());
                assertTrue(result.elapsedMillis() > 0);
                assertEquals(result, host.await(message -> room(message, Phase.FINISHED)).room().outcome());

                host.send(Request.action(Action.REMATCH));
                resumed.await(message -> room(message, Phase.FINISHED)
                        && message.room().players().stream().anyMatch(Protocol.Player::wantsRematch));
                resumed.send(Request.action(Action.REMATCH));
                assertNull(host.await(message -> room(message, Phase.LOBBY)).room().roundId());
                assertNull(resumed.await(message -> room(message, Phase.LOBBY)).room().roundId());
                resumed.send(Request.action(Action.LEAVE));
                resumed.await(Event.LEFT);
                host.await(message -> room(message, Phase.LOBBY) && message.room().players().size() == 1);
            }
        }
    }

    @Test void earliestAcceptedSubmissionWinsEvenIfBothPlayersPass() throws Exception {
        try (var host = new Client(); var guest = new Client()) {
            host.await(Event.HELLO);
            guest.await(Event.HELLO);
            host.send(request(Action.CREATE, "Primero", null, null, null, null, null));
            var hostSession = host.await(Event.SESSION).session();
            guest.send(request(Action.JOIN, "Segundo", hostSession.code(), null, null, null, null));
            guest.await(Event.SESSION);
            host.await(message -> room(message, Phase.LOBBY) && message.room().players().size() == 2);
            host.send(Request.action(Action.START));
            String round = host.await(message -> room(message, Phase.ACTIVE)).room().roundId();
            guest.await(message -> room(message, Phase.ACTIVE));

            String slower = CORRECT.replace("return java.util", """
                    try { Thread.sleep(300); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return java.util""");
            host.send(request(Action.SUBMIT, null, null, null, null, slower, round));
            guest.await(message -> room(message, Phase.ACTIVE)
                    && message.room().players().stream().anyMatch(player -> player.host() && player.evaluating()));
            guest.send(request(Action.SUBMIT, null, null, null, null, CORRECT, round));
            guest.await(message -> room(message, Phase.ACTIVE)
                    && message.room().players().stream().anyMatch(player -> !player.host() && player.evaluating()));

            assertEquals(Status.PASS, host.await(Event.EVALUATION).evaluation().grade().status());
            var outcome = host.await(message -> room(message, Phase.FINISHED)).room().outcome();
            assertEquals(hostSession.playerId(), outcome.winnerId());
            assertEquals(outcome, guest.await(message -> room(message, Phase.FINISHED)).room().outcome());
        }
    }

    private static boolean room(Message message, Phase phase) {
        return message.event() == Event.ROOM && message.room().phase() == phase;
    }

    private static Request request(Action action, String name, String code, String token, String challenge, String source, String round) {
        return new Request(action, name, code, token, challenge, source, round, 0);
    }

    private static final class Client implements WebSocket.Listener, AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        private final StringBuilder payload = new StringBuilder();
        private final WebSocket socket;

        Client() throws Exception {
            socket = http.newWebSocketBuilder().buildAsync(ENDPOINT, this).get(8, TimeUnit.SECONDS);
        }

        void send(Request request) {
            socket.sendText(Protocol.JSON.toJson(request), true).join();
        }

        Message await(Event event) throws InterruptedException {
            return await(message -> message.event() == event);
        }

        Message await(Predicate<Message> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
            while (System.nanoTime() < deadline) {
                Message message = messages.poll(5, TimeUnit.SECONDS);
                if (message == null) send(Request.action(Action.PING));
                else if (predicate.test(message)) return message;
                else if (message.event() == Event.ERROR) {
                    fail("Error inesperado del servidor: " + message.error());
                }
            }
            return fail("El servidor no envió el evento esperado.");
        }

        void disconnect() { socket.abort(); }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            payload.append(data);
            if (last) {
                messages.add(Protocol.JSON.fromJson(payload.toString(), Message.class));
                payload.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override public void close() {
            socket.abort();
            http.shutdownNow();
        }
    }
}
