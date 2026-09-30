package com.streamhub.assistant;

import java.util.List;
import org.springframework.web.client.RestClient;

/** Retrieval: the catalog's hybrid search supplies the only titles the model may talk about. */
public final class CatalogSearch {

    public record Title(long id, String name, List<String> genres, int releaseYear, int durationMinutes,
                        String description) { }

    record Hit(Title title) { }

    record Result(String mode, List<Hit> hits) { }

    private final RestClient http;

    public CatalogSearch(RestClient http) {
        this.http = http;
    }

    public List<Title> search(String question, int limit) {
        Result r = http.get().uri(u -> u.path("/search").queryParam("q", question).queryParam("limit", limit).build())
                .retrieve().body(Result.class);
        return r == null || r.hits() == null ? List.of() : r.hits().stream().map(Hit::title).toList();
    }
}
