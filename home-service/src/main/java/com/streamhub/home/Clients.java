package com.streamhub.home;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;

/** Typed calls to the downstream services, each through its {@link Dependency}. */
public final class Clients {

    public record Progress(long titleId, int positionSeconds, int durationSeconds, Instant updatedAt) { }
    public record Title(long id, String name, List<String> genres, int releaseYear, int durationMinutes,
                        String description) { }
    public record Recs(String source, List<Title> titles) { }

    private Clients() { }

    public static List<Progress> continueWatching(Dependency history, long userId) {
        return history.call(http -> http.get().uri("/history/continue-watching")
                .header("X-User-Id", Long.toString(userId))
                .retrieve().body(new ParameterizedTypeReference<List<Progress>>() { }));
    }

    public static Recs forYou(Dependency recs, long userId, int limit) {
        return recs.call(http -> http.get().uri(u -> u.path("/recs/for-you").queryParam("limit", limit).build())
                .header("X-User-Id", Long.toString(userId))
                .retrieve().body(Recs.class));
    }

    public static List<Title> byGenre(Dependency catalog, String genre, int limit) {
        return catalog.call(http -> http.get().uri(u -> u.path("/titles").queryParam("genre", genre)
                        .queryParam("limit", limit).build())
                .retrieve().body(new ParameterizedTypeReference<List<Title>>() { }));
    }

    public static List<Title> titles(Dependency catalog, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return catalog.call(http -> http.get().uri(u -> u.path("/titles/batch").queryParam("ids", csv).build())
                .retrieve().body(new ParameterizedTypeReference<List<Title>>() { }));
    }
}
