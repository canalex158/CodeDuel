package dev.codeduel.plugin;

import dev.codeduel.shared.Protocol;

import java.net.URI;
import java.util.Locale;

final class Endpoint {
    static final String DEFAULT = "ws://127.0.0.1:8080/duel";

    private Endpoint() {}

    static URI parse(String text) {
        URI input = URI.create(text.strip());
        String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase(Locale.ROOT);
        String host = input.getHost();
        if (host == null || input.getUserInfo() != null || input.getQuery() != null || input.getFragment() != null
                || input.getPort() == 0 || input.getPort() > 65_535 || !(scheme.equals("ws") || scheme.equals("wss"))) {
            throw new IllegalArgumentException("Introduce una URL WebSocket válida. Ejemplo: wss://duelos.example.com/duel");
        }
        if ("ws".equals(scheme) && !host.equalsIgnoreCase("localhost") && !host.equals("127.0.0.1") && !host.equals("[::1]")) {
            throw new IllegalArgumentException("Usa wss:// para servidores remotos.");
        }
        String path = input.getRawPath();
        if (path == null || path.isEmpty() || path.equals("/")) path = Protocol.SOCKET_PATH;
        return URI.create(scheme + "://" + input.getRawAuthority() + path);
    }
}
