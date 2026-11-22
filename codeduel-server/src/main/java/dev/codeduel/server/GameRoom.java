package dev.codeduel.server;

import dev.codeduel.shared.Catalog;
import dev.codeduel.shared.Protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

final class GameRoom {
    static final long COUNTDOWN_MS = 3_000;
    static final long ROUND_MS = 30 * 60_000;
    static final long GRACE_MS = 60_000;

    final String code;
    final List<Seat> seats = new ArrayList<>(2);
    String challengeId = Catalog.all().getFirst().id();
    Round round;

    GameRoom(String code) { this.code = code; }

    Protocol.Phase phase() { return round == null ? Protocol.Phase.LOBBY : round.phase; }

    Protocol.Room snapshot() {
        var players = seats.stream().map(seat -> new Protocol.Player(seat.id, seat.name, seats.getFirst() == seat,
                seat.client != null, seat.evaluating, seat.rematch)).toList();
        return new Protocol.Room(code, phase(), players, challengeId, round == null ? null : round.id,
                round == null ? 0 : round.startsAt, round == null ? 0 : round.deadline,
                round == null ? null : round.outcome);
    }

    static final class Seat {
        final String id = UUID.randomUUID().toString();
        final String name;
        final String token;
        final GameRoom room;
        DuelSocket.Peer client;
        long disconnectedAt;
        long lastJobAt;
        boolean left;
        boolean evaluating;
        boolean rematch;
        Protocol.Evaluation lastEvaluation;

        Seat(GameRoom room, String name, String token) {
            this.room = room;
            this.name = name;
            this.token = token;
        }
    }

    static final class Round {
        final String id = UUID.randomUUID().toString();
        final long startsAt;
        final long deadline;
        final Deque<Submission> submissions = new ArrayDeque<>();
        Protocol.Phase phase = Protocol.Phase.COUNTDOWN;
        Protocol.Outcome outcome;
        String stopReason;

        Round(long now) {
            startsAt = now + COUNTDOWN_MS;
            deadline = startsAt + ROUND_MS;
        }
    }

    static final class Submission {
        final Seat seat;
        final long receivedAt;
        Protocol.Grade grade;

        Submission(Seat seat, long receivedAt) {
            this.seat = seat;
            this.receivedAt = receivedAt;
        }
    }
}
