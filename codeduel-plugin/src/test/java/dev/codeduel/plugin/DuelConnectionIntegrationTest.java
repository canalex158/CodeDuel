package dev.codeduel.plugin;

import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Action;
import dev.codeduel.shared.Protocol.Event;
import dev.codeduel.shared.Protocol.Message;
import dev.codeduel.shared.Protocol.Phase;
import dev.codeduel.shared.Protocol.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CODEDUEL_TEST_ENDPOINT", matches = "wss?://.+")
class DuelConnectionIntegrationTest {
    @Test void pluginTransportAutomaticallyRecoversAClosedSocketAndKeepsTheRound() throws Exception {
        URI uri = Endpoint.parse(System.getenv("CODEDUEL_TEST_ENDPOINT"));
        var host = new RecordingObserver();
        var guest = new RecordingObserver();

        try (var one = new DuelConnection(host); var two = new DuelConnection(guest)) {
            one.begin(uri, new Request(Action.CREATE, "Editor Uno", null, null, null, null, null, 0));
            var oneSession = host.await(Event.SESSION).session();
            two.begin(uri, new Request(Action.JOIN, "Editor Dos", oneSession.code(), null, null, null, null, 0));
            var twoSession = guest.await(Event.SESSION).session();
            host.awaitRoom(Phase.LOBBY, 2);

            one.send(Request.action(Action.START));
            String round = host.awaitRoom(Phase.ACTIVE, 2).roundId();
            assertEquals(round, guest.awaitRoom(Phase.ACTIVE, 2).roundId());

            // Replacing an existing socket exercises the same automatic recovery as a network interruption.
            try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                WebSocket replacement = http.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {})
                        .get(5, TimeUnit.SECONDS);
                try {
                    replacement.sendText(Protocol.JSON.toJson(new Request(Action.RESUME, null, twoSession.code(),
                            twoSession.token(), null, null, null, 0)), true).join();
                    assertEquals(twoSession, guest.await(Event.SESSION).session());
                    assertEquals(round, guest.awaitRoom(Phase.ACTIVE, 2).roundId());
                    assertTrue(guest.states.contains(DuelConnection.State.RECONNECTING));
                } finally {
                    replacement.abort();
                }
            }

            two.leave();
            guest.await(Event.LEFT);
            assertEquals("PLAYER_LEFT", host.awaitRoom(Phase.FINISHED, 2).outcome().reason());
        }
    }

    private static final class RecordingObserver implements DuelConnection.Observer {
        private final LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<DuelConnection.State> states = new LinkedBlockingQueue<>();

        @Override public void state(DuelConnection.State state) { states.add(state); }
        @Override public void message(Message message) { messages.add(message); }
        @Override public void error(String detail) {
            message(new Message(Event.ERROR, 0, null, null, null, null, new Protocol.ErrorNotice("TRANSPORT", detail), 0));
        }
        @Override public void clock(long millis, long nanos) {}

        Message await(Event event) throws InterruptedException {
            while (true) {
                var message = messages.poll(12, TimeUnit.SECONDS);
                assertNotNull(message, "No se recibió " + event);
                if (message.event() == event) return message;
                if (message.event() == Event.ERROR) fail(message.error().message());
            }
        }

        Protocol.Room awaitRoom(Phase phase, int players) throws InterruptedException {
            while (true) {
                var room = await(Event.ROOM).room();
                if (room.phase() == phase && room.players().size() == players) return room;
            }
        }
    }
}
