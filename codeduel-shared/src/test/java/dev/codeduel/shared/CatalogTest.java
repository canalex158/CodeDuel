package dev.codeduel.shared;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {
    @Test void catalogHasFifteenUniquePlayableChallenges() {
        assertEquals(15, Catalog.all().size());
        for (var difficulty : Protocol.Difficulty.values()) {
            assertEquals(5, Catalog.all().stream().filter(item -> item.difficulty() == difficulty).count());
        }
        var ids = new HashSet<String>();
        for (var challenge : Catalog.all()) {
            assertTrue(ids.add(challenge.id()));
            assertFalse(challenge.description().isBlank());
            assertTrue(challenge.starter().contains(challenge.signature()));
            assertTrue(challenge.examples().size() >= 3);
        }
    }

    @Test void helloPayloadRoundTripsWithoutHiddenVectors() {
        var hello = new Protocol.Hello(Protocol.VERSION, Catalog.all());
        String json = Protocol.JSON.toJson(hello);
        assertFalse(json.contains("hiddenCases"));
        var decoded = Protocol.JSON.fromJson(json, Protocol.Hello.class);
        assertEquals(hello, decoded);
        assertEquals(15, Arrays.stream(Protocol.Difficulty.values())
                .mapToLong(level -> decoded.challenges().stream().filter(c -> c.difficulty() == level).count()).sum());
    }
}
