package dev.codeduel.shared;

import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class Catalog {
    private static final List<Protocol.Challenge> CHALLENGES = read();

    private Catalog() {}

    public static List<Protocol.Challenge> all() { return CHALLENGES; }

    public static boolean contains(String id) {
        return CHALLENGES.stream().anyMatch(challenge -> challenge.id().equals(id));
    }

    private static List<Protocol.Challenge> read() {
        var source = Objects.requireNonNull(Catalog.class.getResourceAsStream("/codeduel/challenges.json"));
        try (var reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            List<Protocol.Challenge> catalog = Protocol.JSON.fromJson(reader,
                    new TypeToken<List<Protocol.Challenge>>() {});
            return List.copyOf(catalog);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
