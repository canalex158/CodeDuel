package dev.codeduel.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

public final class Protocol {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final int VERSION = 1;
    public static final String SOCKET_PATH = "/duel";
    public static final int MAX_SOURCE_BYTES = 16_384;
    public static final int MAX_MESSAGE_BYTES = 110_000;

    private Protocol() {}

    public enum Action { CREATE, JOIN, RESUME, SELECT, START, RUN, SUBMIT, REMATCH, LEAVE, PING }
    public enum Event { HELLO, SESSION, ROOM, EVALUATION, LEFT, ERROR, PONG }
    public enum Phase { LOBBY, COUNTDOWN, ACTIVE, FINISHED }
    public enum Difficulty {
        BASIC("Fácil"), INTERMEDIATE("Intermedio"), ADVANCED("Difícil");
        private final String label;
        Difficulty(String label) { this.label = label; }
        public String label() { return label; }
    }
    public enum Status {
        PASS, WRONG_ANSWER, COMPILE_ERROR, RUNTIME_ERROR, TIME_LIMIT, UNAVAILABLE
    }

    public record Request(Action action, String name, String code, String token, String challengeId, String source,
                          String roundId, long pingAt) {
        public static Request action(Action action) { return new Request(action, null, null, null, null, null, null, 0); }
    }

    public record Message(Event event, long serverTime, Hello hello, Session session, Room room, Evaluation evaluation,
                          ErrorNotice error, long pingAt) {}

    public record Hello(int version, List<Challenge> challenges) {}
    public record Session(String code, String playerId, String token) {}
    public record ErrorNotice(String code, String message) {}
    public record Example(String input, String output) {}
    public record Challenge(String id, String title, String description, Difficulty difficulty,
                            String signature, String starter, List<Example> examples) {
        @Override public String toString() { return title + " · " + difficulty.label(); }
    }

    public record Player(String id, String name, boolean host, boolean connected, boolean evaluating,
                         boolean wantsRematch) {}
    public record Room(String code, Phase phase, List<Player> players, String challengeId, String roundId,
                       long startsAt, long deadline, Outcome outcome) {}
    public record Outcome(String winnerId, String winnerName, long elapsedMillis, String reason) {}

    public record Job(String challengeId, String source, boolean submission) {}
    public record Grade(Status status, int passedPublic, int totalPublic, String detail) {}
    public record Evaluation(String roundId, boolean submission, Grade grade) {}
}
