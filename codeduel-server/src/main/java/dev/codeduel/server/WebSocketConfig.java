package dev.codeduel.server;

import dev.codeduel.shared.Protocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {
    private final DuelSocket socket;

    WebSocketConfig(DuelSocket socket) { this.socket = socket; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, Protocol.SOCKET_PATH).addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                return !request.getHeaders().containsKey(HttpHeaders.ORIGIN);
            }

            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Exception failure) {}
        });
    }

    @Bean ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(Protocol.MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(1_024);
        container.setMaxSessionIdleTimeout(50_000L);
        container.setAsyncSendTimeout(4_000L);
        return container;
    }
}
