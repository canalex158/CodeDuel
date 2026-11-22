package dev.codeduel.judge;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import dev.codeduel.shared.Catalog;
import dev.codeduel.shared.Protocol;

import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

record Suite(String id, String returnType, List<String> parameterTypes,
             List<Case> publicCases, List<Case> hiddenCases) {
    record Case(List<JsonElement> arguments, JsonElement expected) {}

    static Map<String, Suite> load() {
        var source = Objects.requireNonNull(Suite.class.getResourceAsStream("/codeduel/suites.json"));
        try (var reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            List<Suite> all = Protocol.JSON.fromJson(reader, new TypeToken<List<Suite>>() {});
            var suites = all.stream().collect(Collectors.toUnmodifiableMap(Suite::id, Function.identity()));
            if (!suites.keySet().equals(Catalog.all().stream().map(Protocol.Challenge::id).collect(Collectors.toSet()))) {
                throw new IllegalStateException("Los catálogos del servidor y el evaluador no coinciden");
            }
            return suites;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
