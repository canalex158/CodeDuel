package dev.codeduel.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import dev.codeduel.shared.Protocol;
import dev.codeduel.shared.Protocol.Action;
import dev.codeduel.shared.Protocol.Challenge;
import dev.codeduel.shared.Protocol.Grade;
import dev.codeduel.shared.Protocol.Message;
import dev.codeduel.shared.Protocol.Phase;
import dev.codeduel.shared.Protocol.Player;
import dev.codeduel.shared.Protocol.Request;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class DuelPanel extends JPanel implements Disposable, DuelConnection.Observer {
    private static final String SERVER_KEY = "codeduel.server";
    private static final String NAME_KEY = "codeduel.name";

    private final Project project;
    private final DuelConnection connection;
    private final SolutionEditor editor;
    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final JBLabel network = new JBLabel("Sin conexión");
    private final JBTextArea notice = textArea(2);
    private final JBTextField playerName = new JBTextField();
    private final JBTextField invite = new JBTextField();
    private final JButton create = new JButton("Crear partida");
    private final JButton join = new JButton("Unirse a partida");
    private final JButton settings = new JButton("Configurar servidor…");
    private final JBLabel roomCode = title("Código de sala");
    private final JBLabel lobbyHost = new JBLabel();
    private final JBLabel lobbyGuest = new JBLabel();
    private final ComboBox<Challenge> picker = new ComboBox<>();
    private final JButton start = new JButton("Iniciar duelo");
    private final JBLabel timer = title("00:00");
    private final JBLabel rival = new JBLabel();
    private final JBLabel yourState = new JBLabel();
    private final JBLabel rivalState = new JBLabel();
    private final JBTextArea instructions = textArea(10);
    private final JBTextArea feedback = textArea(4);
    private final JButton open = new JButton("Abrir Solution.java");
    private final JButton run = new JButton("Ejecutar pruebas");
    private final JButton submit = new JButton("Enviar solución");
    private final JBLabel winner = title("Resultado");
    private final JBLabel duration = new JBLabel();
    private final JBTextArea lastGrade = textArea(6);
    private final JBLabel rematchState = new JBLabel();
    private final JButton rematch = new JButton("Pedir revancha");
    private final Timer ticker;

    private Protocol.Session session;
    private Protocol.Room room;
    private Protocol.Evaluation evaluation;
    private List<Challenge> challenges = List.of();
    private List<Challenge> pickerItems = List.of();
    private DuelConnection.State state = DuelConnection.State.OFFLINE;
    private boolean selecting;
    private boolean entering;
    private boolean jobPending;
    private boolean disposed;
    private String autoOpenedRound;
    private long syncedMillis;
    private long syncedNanos;

    DuelPanel(Project project) {
        super(new BorderLayout(0, JBUI.scale(12)));
        this.project = project;
        editor = new SolutionEditor(project);
        setBorder(JBUI.Borders.empty(14));
        connection = new DuelConnection(this);

        JBLabel brand = title("CodeDuel");
        brand.setIcon(IconLoader.getIcon("/icons/duel.svg", DuelPanel.class));
        brand.setIconTextGap(JBUI.scale(8));
        add(brand, BorderLayout.NORTH);

        cards.add(menu(), "menu");
        cards.add(lobby(), "room");
        cards.add(game(), "game");
        cards.add(results(), "result");
        var scroller = new JBScrollPane(cards);
        scroller.setBorder(JBUI.Borders.empty());
        scroller.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroller, BorderLayout.CENTER);

        var status = column();
        status.add(network);
        status.add(notice);
        notice.setVisible(false);
        add(status, BorderLayout.SOUTH);
        ticker = new Timer(100, event -> updateTime());
        ticker.start();
        render();
    }

    private JPanel menu() {
        playerName.setText(PropertiesComponent.getInstance().getValue(NAME_KEY, ""));
        create.addActionListener(event -> enter(Action.CREATE));
        join.addActionListener(event -> enter(Action.JOIN));
        invite.addActionListener(event -> enter(Action.JOIN));
        settings.addActionListener(event -> configure());
        var contents = column();
        contents.add(labelFor("Nombre de jugador", playerName));
        contents.add(playerName);
        contents.add(create);
        gap(contents, 12);
        contents.add(labelFor("Código de invitación", invite));
        contents.add(invite);
        contents.add(join);
        gap(contents, 12);
        contents.add(settings);
        return top(contents);
    }

    private JPanel lobby() {
        var contents = column();
        contents.add(roomCode);
        contents.add(button("Copiar código", () -> {
            if (room == null) return;
            CopyPasteManager.getInstance().setContents(new StringSelection(room.code()));
            showNotice("Código copiado.");
        }));
        gap(contents, 12);
        contents.add(lobbyHost);
        contents.add(lobbyGuest);
        gap(contents, 12);
        contents.add(labelFor("Reto de Java", picker));
        contents.add(picker);
        picker.addActionListener(event -> {
            if (!selecting && picker.getSelectedItem() instanceof Challenge item) {
                connection.send(new Request(Action.SELECT, null, null, null, item.id(), null, null, 0));
            }
        });
        start.addActionListener(event -> connection.send(Request.action(Action.START)));
        contents.add(start);
        contents.add(button("Salir de la sala", connection::leave));
        return top(contents);
    }

    private JPanel game() {
        var contents = column();
        contents.add(timer);
        contents.add(rival);
        contents.add(yourState);
        contents.add(rivalState);
        gap(contents, 12);
        contents.add(instructions);
        gap(contents, 12);
        open.addActionListener(event -> openSolution());
        run.addActionListener(event -> sendCode(Action.RUN));
        submit.addActionListener(event -> sendCode(Action.SUBMIT));
        contents.add(open);
        contents.add(run);
        contents.add(submit);
        contents.add(feedback);
        contents.add(button("Salir del duelo", connection::leave));
        return top(contents);
    }

    private JPanel results() {
        var contents = column();
        contents.add(winner);
        contents.add(duration);
        gap(contents, 12);
        contents.add(lastGrade);
        contents.add(rematchState);
        rematch.addActionListener(event -> connection.send(Request.action(Action.REMATCH)));
        contents.add(rematch);
        contents.add(button("Volver al menú", connection::leave));
        return top(contents);
    }

    private void enter(Action action) {
        if (entering || session != null) return;
        String name = playerName.getText().strip();
        String code = invite.getText().strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 24) {
            showNotice("Escribe un nombre de 1 a 24 caracteres.");
            playerName.requestFocusInWindow();
            return;
        }
        if (action == Action.JOIN && !code.matches("[A-Z2-9]{8}")) {
            showNotice("Introduce el código de invitación de 8 caracteres.");
            invite.requestFocusInWindow();
            return;
        }
        try {
            var endpoint = Endpoint.parse(server());
            PropertiesComponent.getInstance().setValue(NAME_KEY, name);
            showNotice("");
            entering = true;
            render();
            connection.begin(endpoint, new Request(action, name, code, null, null, null, null, 0));
        } catch (IllegalArgumentException e) {
            showNotice("La URL del servidor no es válida. Revísala en Configurar servidor.");
        }
    }

    private void configure() {
        String candidate = Messages.showInputDialog(project, "URL del servidor WebSocket", "Servidor de CodeDuel",
                Messages.getQuestionIcon(), server(), null);
        if (candidate == null) return;
        try {
            PropertiesComponent.getInstance().setValue(SERVER_KEY, Endpoint.parse(candidate).toString());
            showNotice("URL guardada.");
        } catch (IllegalArgumentException e) {
            showNotice("Introduce una URL wss:// válida. Usa ws:// solo en el equipo local.");
        }
    }

    private static String server() { return PropertiesComponent.getInstance().getValue(SERVER_KEY, Endpoint.DEFAULT); }

    private boolean openSolution() {
        var challenge = selectedChallenge();
        if (room != null && challenge != null && (room.phase() == Phase.ACTIVE || room.phase() == Phase.FINISHED)) {
            try {
                if (editor.open(room, challenge)) return true;
            } catch (IOException | RuntimeException e) {
                com.intellij.openapi.diagnostic.Logger.getInstance(DuelPanel.class).warn("CodeDuel scratch editor failed", e);
            }
            showNotice("No se pudo abrir Solution.java. Prueba a abrirlo de nuevo.");
        }
        return false;
    }

    private void sendCode(Action action) {
        if (room == null || room.phase() != Phase.ACTIVE) return;
        String source = editor.source(room.roundId());
        if (source == null && openSolution()) source = editor.source(room.roundId());
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_SOURCE_BYTES) {
            showNotice("La solución debe contener entre 1 y 16 384 bytes de código Java.");
            return;
        }
        showNotice("");
        jobPending = true;
        render();
        connection.send(new Request(action, null, null, null, null, source, room.roundId(), 0));
    }

    @Override public void state(DuelConnection.State newState) {
        onEdt(() -> {
            state = newState;
            if (state == DuelConnection.State.OFFLINE) entering = false;
            network.setText(switch (state) {
                case OFFLINE -> "Sin conexión";
                case CONNECTING -> "Conectando…";
                case ONLINE -> "Conectado al servidor";
                case RECONNECTING -> "Reconectando con la sala…";
            });
            if (state == DuelConnection.State.RECONNECTING) jobPending = false;
            render();
        });
    }

    @Override public void message(Message message) {
        onEdt(() -> {
            switch (message.event()) {
                case HELLO -> challenges = List.copyOf(message.hello().challenges());
                case SESSION -> {
                    session = message.session();
                    entering = false;
                    showNotice("");
                }
                case ROOM -> {
                    if (room == null || !Objects.equals(room.roundId(), message.room().roundId())) {
                        evaluation = null;
                        jobPending = false;
                        feedback.setText("");
                        showNotice("");
                    }
                    room = message.room();
                    Player player = local();
                    if (player != null && player.evaluating()) jobPending = false;
                    if (room.phase() == Phase.ACTIVE && !Objects.equals(room.roundId(), autoOpenedRound)) {
                        autoOpenedRound = room.roundId();
                        openSolution();
                    }
                }
                case EVALUATION -> {
                    if (room != null && Objects.equals(room.roundId(), message.evaluation().roundId())) {
                        evaluation = message.evaluation();
                        jobPending = false;
                    }
                }
                case ERROR -> {
                    entering = false;
                    jobPending = false;
                    showNotice(message.error().message());
                }
                case LEFT -> {
                    session = null;
                    room = null;
                    evaluation = null;
                    autoOpenedRound = null;
                    entering = false;
                    jobPending = false;
                    showNotice("");
                }
                default -> {}
            }
            render();
        });
    }

    @Override public void error(String detail) {
        onEdt(() -> {
            jobPending = false;
            entering = false;
            showNotice(detail);
            render();
        });
    }

    @Override public void clock(long serverMillis, long monotonicNanos) {
        onEdt(() -> {
            syncedMillis = serverMillis;
            syncedNanos = monotonicNanos;
            updateTime();
        });
    }

    private void render() {
        boolean online = state == DuelConnection.State.ONLINE;
        if (room == null) {
            layout.show(cards, "menu");
            create.setEnabled(!entering);
            join.setEnabled(!entering);
            settings.setEnabled(!entering);
            return;
        }
        Player local = local();
        Player opponent = opponent();
        Challenge challenge = selectedChallenge();
        switch (room.phase()) {
            case LOBBY -> {
                layout.show(cards, "room");
                roomCode.setText("Sala " + room.code());
                lobbyHost.setText(playerLabel("Anfitrión", room.players().isEmpty() ? null : room.players().getFirst()));
                lobbyGuest.setText(playerLabel("Invitado", room.players().size() < 2 ? null : room.players().get(1)));
                selecting = true;
                if (!pickerItems.equals(challenges)) {
                    picker.setModel(new DefaultComboBoxModel<>(challenges.toArray(Challenge[]::new)));
                    pickerItems = challenges;
                }
                picker.setSelectedItem(challenge);
                selecting = false;
                boolean host = online && local != null && local.host();
                picker.setEnabled(host);
                start.setEnabled(host && opponent != null && opponent.connected());
            }
            case COUNTDOWN, ACTIVE -> {
                layout.show(cards, "game");
                updateTime();
                rival.setText("Rival · " + (opponent == null ? "—" : opponent.name()));
                yourState.setText("Tú · " + playerState(local));
                rivalState.setText("Rival · " + playerState(opponent));
                if (challenge != null) {
                    var description = new StringBuilder(challenge.toString()).append("\n\n")
                            .append(challenge.description()).append("\n\n").append(challenge.signature());
                    for (Protocol.Example example : challenge.examples()) {
                        description.append("\n\nEntrada · ").append(example.input())
                                .append("\nSalida · ").append(example.output());
                    }
                    assign(instructions, description.toString());
                }
                boolean active = room.phase() == Phase.ACTIVE;
                open.setEnabled(active);
                boolean canSend = online && active && local != null && !local.evaluating() && !jobPending;
                run.setEnabled(canSend);
                submit.setEnabled(canSend);
                assign(feedback, evaluationText());
            }
            case FINISHED -> {
                layout.show(cards, "result");
                var outcome = room.outcome();
                winner.setText(outcome.winnerName() == null ? "Sin ganador" : "Ganador · " + outcome.winnerName());
                duration.setText("Tiempo · " + time(outcome.elapsedMillis(), true));
                String reason = switch (outcome.reason()) {
                    case "TIME_EXPIRED" -> "Terminó el tiempo del duelo.";
                    case "PLAYER_LEFT" -> "Un participante abandonó la sala.";
                    default -> "La solución ganadora superó todos los casos.";
                };
                assign(lastGrade, reason + "\n\n" + evaluationText());
                boolean requested = local != null && local.wantsRematch();
                rematchState.setText(requested ? "Esperando al rival…" :
                        opponent != null && opponent.wantsRematch() ? "Tu rival pidió la revancha." : "");
                rematch.setEnabled(online && !requested);
            }
        }
    }

    private void updateTime() {
        if (room == null || syncedNanos == 0) return;
        long millis = syncedMillis + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - syncedNanos);
        if (room.phase() == Phase.COUNTDOWN) {
            timer.setText("Empieza en " + Math.max(1, (room.startsAt() - millis + 999) / 1_000));
        } else if (room.phase() == Phase.ACTIVE) {
            long elapsed = Math.max(0, Math.min(room.deadline(), millis) - room.startsAt());
            timer.setText("Tiempo · " + time(elapsed, false));
        }
    }

    private String evaluationText() {
        if (evaluation == null) return room != null && room.phase() == Phase.FINISHED ? "Sin evaluación individual." : "";
        Grade grade = evaluation.grade();
        String mode = evaluation.submission() ? "Envío oficial" : "Pruebas públicas";
        String status = switch (grade.status()) {
            case PASS -> "Correcto";
            case WRONG_ANSWER -> "Respuesta incorrecta";
            case COMPILE_ERROR -> "Error de compilación";
            case RUNTIME_ERROR -> "Error de ejecución";
            case TIME_LIMIT -> "Límite excedido";
            case UNAVAILABLE -> "Evaluador no disponible";
        };
        return mode + " · " + status + "\n" + grade.passedPublic() + "/" + grade.totalPublic()
                + " casos públicos superados\n" + grade.detail();
    }

    private Challenge selectedChallenge() {
        if (room == null) return null;
        return challenges.stream().filter(challenge -> challenge.id().equals(room.challengeId())).findFirst().orElse(null);
    }

    private Player local() {
        if (session == null || room == null) return null;
        return room.players().stream().filter(player -> player.id().equals(session.playerId())).findFirst().orElse(null);
    }

    private Player opponent() {
        if (session == null || room == null) return null;
        return room.players().stream().filter(player -> !player.id().equals(session.playerId())).findFirst().orElse(null);
    }

    private static String playerState(Player player) {
        if (player == null) return "En espera";
        if (!player.connected()) return "Sin conexión";
        if (player.evaluating()) return "Evaluando solución";
        return "Conectado";
    }

    private static String playerLabel(String role, Player player) {
        return role + " · " + (player == null ? "En espera" : player.name() + " · " + playerState(player));
    }

    private static String time(long millis, boolean fraction) {
        return String.format(Locale.ROOT, fraction ? "%02d:%02d.%03d" : "%02d:%02d",
                millis / 60_000, millis / 1_000 % 60, millis % 1_000);
    }

    private void showNotice(String text) {
        assign(notice, text);
        notice.setVisible(!text.isBlank());
        revalidate();
        repaint();
    }

    private void onEdt(Runnable callback) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) callback.run();
        }, ModalityState.nonModal());
    }

    private static JPanel top(JPanel contents) {
        var panel = new JPanel(new BorderLayout());
        panel.add(contents, BorderLayout.NORTH);
        return panel;
    }

    private static JPanel column() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static void gap(JPanel panel, int size) {
        panel.add(Box.createVerticalStrut(JBUI.scale(size)));
    }

    private static JButton button(String text, Runnable action) {
        var button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JBLabel title(String text) {
        var label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 2));
        return label;
    }

    private static JBLabel labelFor(String text, Component target) {
        var label = new JBLabel(text);
        label.setLabelFor(target);
        return label;
    }

    private static JBTextArea textArea(int rows) {
        // An area with a wrapping width hint can still adapt to the tool window.
        var area = new JBTextArea(rows, 23);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty(3, 0));
        return area;
    }

    private static void assign(JBTextArea area, String value) {
        if (!area.getText().equals(value)) {
            area.setText(value);
            area.setCaretPosition(0);
        }
    }

    @Override public void dispose() {
        disposed = true;
        ticker.stop();
        connection.close();
    }
}
