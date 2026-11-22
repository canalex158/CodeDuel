package dev.codeduel.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndpointTest {
    @Test void localAndSecureEndpointsGetDefaultPath() {
        assertEquals("ws://127.0.0.1:18080/duel", Endpoint.parse(" ws://127.0.0.1:18080 ").toString());
        assertEquals("ws://[::1]:18080/duel", Endpoint.parse("ws://[::1]:18080").toString());
        assertEquals("wss://duelos.example.com/duel", Endpoint.parse("wss://duelos.example.com/").toString());
        assertEquals("wss://duelos.example.com/partidas", Endpoint.parse("wss://duelos.example.com/partidas").toString());
    }

    @Test void insecureRemoteOrAmbiguousUrlsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("ws://duelos.example.com"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("https://duelos.example.com"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("wss://x@y.example/duel"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("wss://y.example/duel?secret=value"));
    }
}
