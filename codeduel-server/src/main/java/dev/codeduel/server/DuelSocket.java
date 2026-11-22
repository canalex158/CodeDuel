package dev.codeduel.server;

import com.google.gson.JsonParseException;
import dev.codeduel.shared.Protocol;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
final class DuelSocket extends TextWebSocketHandler {
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(192);
    private final GameService games;

    DuelSocket(GameService games) { this.games = games; }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!capacity.tryAcquire()) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        session.setTextMessageSizeLimit(Protocol.MAX_MESSAGE_BYTES);
        var peer = new Peer(session, this);
        peers.put(session.getId(), peer);
        games.hello(peer);
        Thread.ofVirtual().name("codeduel-websocket").start(peer::write);
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null || !peer.isOpen()) return;
        if (message.getPayloadLength() > Protocol.MAX_MESSAGE_BYTES
                || message.getPayload().getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_MESSAGE_BYTES
                || !peer.allowMessage()) {
            peer.close();
            return;
        }
        try {
            games.receive(peer, Protocol.JSON.fromJson(message.getPayload(), Protocol.Request.class));
        } catch (JsonParseException e) {
            GameService.error(peer, "BAD_JSON", "El mensaje no se pudo leer.");
        }
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.close();
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.close();
    }

    @Scheduled(fixedDelay = 5_000)
    void reap() {
        long limit = System.nanoTime() - java.time.Duration.ofSeconds(40).toNanos();
        peers.values().stream().filter(peer -> peer.lastMessage < limit).forEach(Peer::close);
    }

    private void remove(Peer peer) {
        if (peers.remove(peer.session.getId(), peer)) {
            capacity.release();
            games.disconnected(peer);
        }
    }

    static final class Peer {
        private final WebSocketSession session;
        private final DuelSocket handler;
        private final ArrayBlockingQueue<Protocol.Message> outbox = new ArrayBlockingQueue<>(48);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private volatile Thread writer;
        private volatile long lastMessage = System.nanoTime();
        private double credits = 24;
        GameRoom.Seat seat;

        Peer(WebSocketSession session, DuelSocket handler) {
            this.session = session;
            this.handler = handler;
        }

        boolean isOpen() { return open.get(); }

        synchronized boolean allowMessage() {
            long now = System.nanoTime();
            credits = Math.min(24, credits + (now - lastMessage) / 1_000_000_000.0 * 6);
            lastMessage = now;
            if (credits < 1) return false;
            credits--;
            return true;
        }

        void send(Protocol.Message message) {
            if (open.get() && !outbox.offer(message)) close();
        }

        void write() {
            writer = Thread.currentThread();
            try {
                while (open.get()) {
                    session.sendMessage(new TextMessage(Protocol.JSON.toJson(outbox.take())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException ignored) {
                // A broken socket follows the same cleanup path as a clean disconnect.
            } finally {
                close();
            }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            Thread active = writer;
            if (active != null && active != Thread.currentThread()) active.interrupt();
            Thread.startVirtualThread(() -> {
                handler.remove(this);
                try {
                    if (session.isOpen()) session.close();
                } catch (IOException | IllegalStateException ignored) {
                    // The GameService no longer considers this socket connected.
                }
            });
        }
    }
}
