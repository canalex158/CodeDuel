package dev.codeduel.server;

import dev.codeduel.server.GameRoom.Round;
import dev.codeduel.server.GameRoom.Seat;
import dev.codeduel.server.GameRoom.Submission;
import dev.codeduel.shared.Catalog;
import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Action;
import dev.codeduel.shared.Protocol.Event;
import dev.codeduel.shared.Protocol.Grade;
import dev.codeduel.shared.Protocol.Message;
import dev.codeduel.shared.Protocol.Phase;
import dev.codeduel.shared.Protocol.Request;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
final class GameService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_ROOMS = 96;
    private static final long JOB_COOLDOWN_MS = 1_500;
    private static final long START_NANOS = System.nanoTime();
    private static final long START_MILLIS = System.currentTimeMillis();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, GameRoom> rooms = new HashMap<>();
    private final JudgeGateway judge;
    private final ThreadPoolExecutor evaluations = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("codeduel-evaluation-", 0).factory());

    GameService(JudgeGateway judge) { this.judge = judge; }

    synchronized void hello(DuelSocket.Peer client) {
        client.send(new Message(Event.HELLO, now(), new Protocol.Hello(Protocol.VERSION, Catalog.all()),
                null, null, null, null, 0));
    }

    synchronized void receive(DuelSocket.Peer client, Request request) {
        if (!client.isOpen()) return;
        if (request == null || request.action() == null) {
            error(client, "BAD_REQUEST", "Petición inválida.");
            return;
        }
        long now = now();
        switch (request.action()) {
            case PING -> client.send(new Message(Event.PONG, now, null, null, null, null, null, request.pingAt()));
            case CREATE, JOIN, RESUME -> enter(client, request, now);
            default -> {
                Seat seat = client.seat;
                if (seat == null || seat.client != client) {
                    error(client, "NO_SESSION", "Primero entra en una sala.");
                    return;
                }
                advance(seat.room, now);
                switch (request.action()) {
                    case SELECT -> select(seat, request.challengeId());
                    case START -> start(seat, now);
                    case RUN, SUBMIT -> evaluate(seat, request, now);
                    case REMATCH -> rematch(seat);
                    case LEAVE -> leave(seat, now);
                    default -> throw new IllegalStateException();
                }
            }
        }
    }

    synchronized void disconnected(DuelSocket.Peer client) {
        Seat seat = client.seat;
        if (seat == null || seat.client != client) return;
        client.seat = null;
        seat.client = null;
        seat.disconnectedAt = now();
        broadcast(seat.room);
    }

    @Scheduled(fixedDelay = 100)
    synchronized void tick() {
        long now = now();
        var iterator = rooms.values().iterator();
        while (iterator.hasNext()) {
            GameRoom room = iterator.next();
            advance(room, now);
            if (room.phase() == Phase.LOBBY || room.phase() == Phase.FINISHED) {
                boolean removed = room.seats.removeIf(seat -> expired(seat, now));
                if (room.seats.isEmpty()) iterator.remove();
                else if (removed) {
                    reopenIfReady(room);
                    broadcast(room);
                }
            }
        }
    }

    @PreDestroy
    void stop() { evaluations.shutdownNow(); }

    private void enter(DuelSocket.Peer client, Request request, long now) {
        if (client.seat != null) {
            error(client, "ALREADY_JOINED", "Ya formas parte de una sala.");
            return;
        }
        if (request.action() == Action.RESUME) {
            resume(client, request, now);
            return;
        }

        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 24 || name.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            error(client, "BAD_NAME", "Escribe un nombre de 1 a 24 caracteres.");
            return;
        }

        GameRoom room;
        if (request.action() == Action.CREATE) {
            if (rooms.size() >= MAX_ROOMS) {
                error(client, "BUSY", "El servidor ha alcanzado el límite de salas.");
                return;
            }
            String code;
            do { code = newCode(); } while (rooms.containsKey(code));
            room = new GameRoom(code);
            rooms.put(code, room);
        } else {
            room = rooms.get(normalize(request.code()));
            if (room == null) {
                error(client, "ROOM_NOT_FOUND", "El código de sala no existe.");
                return;
            }
            if (room.seats.size() >= 2) {
                error(client, "ROOM_FULL", "La sala ya tiene dos participantes.");
                return;
            }
            if (room.phase() != Phase.LOBBY) {
                error(client, "ROOM_STARTED", "La sala tiene un duelo en curso o pendiente de revancha.");
                return;
            }
            if (room.seats.stream().anyMatch(seat -> seat.name.equalsIgnoreCase(name))) {
                error(client, "NAME_TAKEN", "Elige un nombre distinto al de tu rival.");
                return;
            }
        }
        byte[] key = new byte[32];
        random.nextBytes(key);
        Seat seat = new Seat(room, name, Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        room.seats.add(seat);
        attach(client, seat);
    }

    private void resume(DuelSocket.Peer client, Request request, long now) {
        GameRoom room = rooms.get(normalize(request.code()));
        if (room != null && request.token() != null && request.token().length() == 43) {
            byte[] incoming = request.token().getBytes(StandardCharsets.UTF_8);
            for (Seat seat : room.seats) {
                if (!expired(seat, now) && java.security.MessageDigest.isEqual(incoming,
                        seat.token.getBytes(StandardCharsets.UTF_8))) {
                    advance(room, now);
                    if (seat.client != null) seat.client.close();
                    attach(client, seat);
                    if (seat.lastEvaluation != null) sendEvaluation(seat, seat.lastEvaluation);
                    return;
                }
            }
        }
        error(client, "SESSION_EXPIRED", "La sesión ha caducado. Vuelve a entrar con un código de sala.");
    }

    private void attach(DuelSocket.Peer client, Seat seat) {
        seat.client = client;
        seat.disconnectedAt = 0;
        client.seat = seat;
        client.send(new Message(Event.SESSION, now(), null, new Protocol.Session(seat.room.code, seat.id, seat.token),
                null, null, null, 0));
        broadcast(seat.room);
    }

    private void select(Seat seat, String challengeId) {
        GameRoom room = seat.room;
        if (!isHost(seat) || room.phase() != Phase.LOBBY || !Catalog.contains(challengeId)) {
            error(seat.client, "BAD_SELECTION", "El anfitrión puede elegir un reto válido desde la sala.");
            return;
        }
        room.challengeId = challengeId;
        broadcast(room);
    }

    private void start(Seat seat, long now) {
        GameRoom room = seat.room;
        if (!isHost(seat) || room.phase() != Phase.LOBBY || room.seats.size() != 2
                || room.seats.stream().anyMatch(player -> player.client == null)) {
            error(seat.client, "CANNOT_START", "El anfitrión necesita dos participantes conectados para iniciar.");
            return;
        }
        room.round = new Round(now);
        for (Seat player : room.seats) {
            player.lastEvaluation = null;
            player.lastJobAt = 0;
        }
        broadcast(room);
    }

    private void evaluate(Seat seat, Request request, long now) {
        GameRoom room = seat.room;
        Round round = room.round;
        if (round == null || round.phase != Phase.ACTIVE || round.stopReason != null
                || !round.id.equals(request.roundId())) {
            error(seat.client, "NOT_ACTIVE", "El duelo no admite envíos en este momento.");
            return;
        }
        if (seat.evaluating || now - seat.lastJobAt < JOB_COOLDOWN_MS) {
            error(seat.client, "WAIT", "Espera antes de volver a evaluar.");
            return;
        }
        String source = request.source();
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_SOURCE_BYTES) {
            error(seat.client, "BAD_SOURCE", "La solución debe contener entre 1 y 16 384 bytes de código Java.");
            return;
        }

        boolean official = request.action() == Action.SUBMIT;
        Submission submission = official ? new Submission(seat, now) : null;
        var job = new Protocol.Job(room.challengeId, source, official);
        try {
            evaluations.execute(() -> completed(seat, round, submission, judge.evaluate(job)));
        } catch (RejectedExecutionException e) {
            error(seat.client, "BUSY", "Hay demasiadas evaluaciones en curso. Vuelve a intentarlo.");
            return;
        }
        seat.lastJobAt = now;
        seat.evaluating = true;
        if (submission != null) round.submissions.addLast(submission);
        broadcast(room);
    }

    private synchronized void completed(Seat seat, Round round, Submission submission, Grade grade) {
        GameRoom room = seat.room;
        if (room.round != round || round.phase == Phase.FINISHED) return;
        seat.evaluating = false;
        var evaluation = new Protocol.Evaluation(round.id, submission != null, grade);
        seat.lastEvaluation = evaluation;
        sendEvaluation(seat, evaluation);
        if (submission != null) submission.grade = grade;
        settle(room);
        broadcast(room);
    }

    private void advance(GameRoom room, long now) {
        Round round = room.round;
        if (round == null || round.phase == Phase.FINISHED) return;
        if (now >= round.deadline || room.seats.stream().anyMatch(seat -> expired(seat, now))) {
            if (round.stopReason == null) {
                round.stopReason = now >= round.deadline ? "TIME_EXPIRED" : "PLAYER_LEFT";
                settle(room);
                broadcast(room);
            }
        } else if (round.phase == Phase.COUNTDOWN && now >= round.startsAt) {
            round.phase = Phase.ACTIVE;
            broadcast(room);
        }
    }

    private void settle(GameRoom room) {
        Round round = room.round;
        while (!round.submissions.isEmpty() && round.submissions.peekFirst().grade != null) {
            Submission first = round.submissions.removeFirst();
            if (first.grade.status() == Protocol.Status.PASS) {
                finish(room, new Protocol.Outcome(first.seat.id, first.seat.name,
                        first.receivedAt - round.startsAt, "ACCEPTED"));
                return;
            }
        }
        if (round.submissions.isEmpty() && round.stopReason != null) {
            long elapsed = Math.max(0, Math.min(now(), round.deadline) - round.startsAt);
            finish(room, new Protocol.Outcome(null, null, elapsed, round.stopReason));
        }
    }

    private void finish(GameRoom room, Protocol.Outcome outcome) {
        room.round.phase = Phase.FINISHED;
        room.round.outcome = outcome;
        room.round.submissions.clear();
        for (Seat player : room.seats) player.evaluating = false;
    }

    private void rematch(Seat seat) {
        GameRoom room = seat.room;
        if (room.phase() != Phase.FINISHED) {
            error(seat.client, "CANNOT_REMATCH", "La revancha se solicita al terminar el duelo.");
            return;
        }
        seat.rematch = true;
        reopenIfReady(room);
        broadcast(room);
    }

    private static void reopenIfReady(GameRoom room) {
        if (room.phase() == Phase.FINISHED
                && room.seats.stream().allMatch(player -> player.client != null && player.rematch)) {
            room.round = null;
            for (Seat player : room.seats) {
                player.rematch = false;
                player.lastEvaluation = null;
            }
        }
    }

    private void leave(Seat seat, long now) {
        DuelSocket.Peer client = seat.client;
        seat.left = true;
        seat.client = null;
        seat.disconnectedAt = now;
        client.seat = null;
        client.send(new Message(Event.LEFT, now, null, null, null, null, null, 0));
        GameRoom room = seat.room;
        if (room.phase() == Phase.LOBBY || room.phase() == Phase.FINISHED) {
            room.seats.remove(seat);
            if (room.seats.isEmpty()) rooms.remove(room.code);
            else {
                reopenIfReady(room);
                broadcast(room);
            }
        } else {
            advance(room, now);
            broadcast(room);
        }
    }

    private void broadcast(GameRoom room) {
        var snapshot = new Message(Event.ROOM, now(), null, null, room.snapshot(), null, null, 0);
        for (Seat seat : room.seats) if (seat.client != null) seat.client.send(snapshot);
    }

    private void sendEvaluation(Seat seat, Protocol.Evaluation evaluation) {
        if (seat.client != null) seat.client.send(new Message(Event.EVALUATION, now(), null, null, null, evaluation, null, 0));
    }

    static void error(DuelSocket.Peer client, String code, String text) {
        client.send(new Message(Event.ERROR, now(), null, null, null, null, new Protocol.ErrorNotice(code, text), 0));
    }

    private String newCode() {
        var code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }

    private static String normalize(String code) {
        return code == null || code.length() > 32 ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean expired(Seat seat, long now) {
        return seat.left || (seat.client == null && now - seat.disconnectedAt >= GameRoom.GRACE_MS);
    }

    private static boolean isHost(Seat seat) { return seat.room.seats.getFirst() == seat; }
    private static long now() {
        return START_MILLIS + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - START_NANOS);
    }
}
