package dev.codeduel.plugin;

import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Action;
import dev.codeduel.shared.Protocol.Message;
import dev.codeduel.shared.Protocol.Request;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class DuelConnection implements AutoCloseable {
    enum State { OFFLINE, CONNECTING, ONLINE, RECONNECTING }

    interface Observer {
        void state(State state);
        void message(Message message);
        void error(String detail);
        void clock(long serverMillis, long monotonicNanos);
    }

    private final Observer observer;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("codeduel-network").factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private URI endpoint;
    private WebSocket socket;
    private int generation;
    private Protocol.Session session;
    private Request pending;
    private boolean ready;
    private long lastReceived;
    private long retryStarted;
    private int attempts;
    private CompletableFuture<WebSocket> outgoing = CompletableFuture.completedFuture(null);

    DuelConnection(Observer observer) {
        this.observer = observer;
        worker.scheduleAtFixedRate(this::heartbeat, 1, 8, TimeUnit.SECONDS);
    }

    void begin(URI target, Request request) {
        dispatch(() -> {
            if (socket != null && endpoint.equals(target) && ready) {
                transmit(request);
                return;
            }
            resetSocket();
            endpoint = target;
            pending = request;
            session = null;
            connect();
        });
    }

    void send(Request request) {
        dispatch(() -> {
            if (ready && socket != null) transmit(request);
            else observer.error("La conexión aún no está lista.");
        });
    }

    void leave() {
        dispatch(() -> {
            if (socket != null && ready && session != null) {
                transmit(Request.action(Action.LEAVE));
            } else {
                resetRoom();
            }
        });
    }

    private void connect() {
        observer.state(session == null ? State.CONNECTING : State.RECONNECTING);
        int current = ++generation;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(6)).buildAsync(endpoint, new Listener(current))
                .whenComplete((webSocket, failure) -> {
                    if (failure != null) dispatch(() -> disconnected(current));
                });
        worker.schedule(() -> {
            if (current == generation && !ready) disconnected(current);
        }, 9, TimeUnit.SECONDS);
    }

    private void handle(int current, Message message) {
        if (current != generation) return;
        lastReceived = System.nanoTime();
        if (message.event() == null) return;
        switch (message.event()) {
            case HELLO -> {
                if (message.hello() == null || message.hello().version() != Protocol.VERSION) {
                    resetRoom();
                    observer.error("La versión del servidor es incompatible con este plugin.");
                    return;
                }
                observer.clock(message.serverTime(), System.nanoTime());
                observer.message(message);
                ready = true;
                if (session != null) {
                    transmit(new Request(Action.RESUME, null, session.code(), session.token(), null, null, null, 0));
                } else {
                    observer.state(State.ONLINE);
                    if (pending != null) transmit(pending);
                    pending = null;
                }
                ping();
            }
            case SESSION -> {
                session = message.session();
                retryStarted = 0;
                attempts = 0;
                observer.message(message);
                observer.state(State.ONLINE);
            }
            case PONG -> {
                long received = System.nanoTime();
                long roundTrip = received - message.pingAt();
                if (message.pingAt() != 0 && roundTrip >= 0 && roundTrip < TimeUnit.SECONDS.toNanos(5)) {
                    observer.clock(message.serverTime() + TimeUnit.NANOSECONDS.toMillis(roundTrip) / 2, received);
                }
            }
            case LEFT -> resetRoom();
            case ERROR -> {
                if (message.error() != null && "SESSION_EXPIRED".equals(message.error().code())) resetRoom();
                observer.message(message);
            }
            default -> observer.message(message);
        }
    }

    private void disconnected(int current) {
        if (current != generation) return;
        resetSocket();
        if (session == null) {
            pending = null;
            observer.state(State.OFFLINE);
            observer.error("Se perdió la conexión con el servidor. Comprueba la URL e inténtalo de nuevo.");
            return;
        }
        if (retryStarted == 0) retryStarted = System.nanoTime();
        if (System.nanoTime() - retryStarted > TimeUnit.SECONDS.toNanos(100)) {
            resetRoom();
            observer.error("Se agotó el tiempo de reconexión.");
            return;
        }
        observer.state(State.RECONNECTING);
        int next = generation;
        long delay = Math.min(5_000, 450L << Math.min(attempts++, 4));
        worker.schedule(() -> { if (next == generation) connect(); }, delay, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        if (!ready || socket == null) return;
        if (System.nanoTime() - lastReceived > TimeUnit.SECONDS.toNanos(24)) disconnected(generation);
        else ping();
    }

    private void ping() {
        transmit(new Request(Action.PING, null, null, null, null, null, null, System.nanoTime()));
    }

    private void transmit(Request request) {
        int current = generation;
        WebSocket connected = socket;
        String json = Protocol.JSON.toJson(request);
        outgoing = outgoing.thenCompose(ignored -> connected.sendText(json, true));
        outgoing.whenComplete((ignored, failure) -> {
            if (failure != null) dispatch(() -> disconnected(current));
        });
    }

    private void resetSocket() {
        generation++;
        ready = false;
        outgoing = CompletableFuture.completedFuture(null);
        if (socket != null) {
            socket.abort();
            socket = null;
        }
    }

    private void resetRoom() {
        resetSocket();
        pending = null;
        session = null;
        retryStarted = 0;
        attempts = 0;
        observer.message(new Message(Protocol.Event.LEFT, 0, null, null, null, null, null, 0));
        observer.state(State.OFFLINE);
    }

    private boolean dispatch(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    @Override public void close() {
        dispatch(this::resetSocket);
        worker.shutdown();
        http.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final int current;
        private final StringBuilder buffer = new StringBuilder();

        Listener(int current) { this.current = current; }

        @Override public void onOpen(WebSocket webSocket) {
            boolean scheduled = dispatch(() -> {
                if (current == generation) {
                    socket = webSocket;
                    lastReceived = System.nanoTime();
                    webSocket.request(1);
                } else webSocket.abort();
            });
            if (!scheduled) webSocket.abort();
        }

        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (buffer.length() + data.length() > Protocol.MAX_MESSAGE_BYTES) {
                dispatch(() -> disconnected(current));
                return null;
            }
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                dispatch(() -> {
                    if (payload.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_MESSAGE_BYTES) {
                        disconnected(current);
                        return;
                    }
                    try {
                        Message message = Protocol.JSON.fromJson(payload, Message.class);
                        if (message != null) handle(current, message);
                    } catch (com.google.gson.JsonParseException e) {
                        disconnected(current);
                    }
                });
            }
            webSocket.request(1);
            return null;
        }

        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            dispatch(() -> disconnected(current));
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            dispatch(() -> disconnected(current));
        }
    }
}
