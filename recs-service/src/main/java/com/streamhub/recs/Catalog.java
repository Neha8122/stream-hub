package com.streamhub.recs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The catalog, which owns titles and their embeddings. Embeddings are
 * cached here: they only change when a title is edited, and every
 * heartbeat needs one.
 */
public class Catalog {

    public record Title(long id, String name, List<String> genres, int releaseYear, int durationMinutes,
                        String description) { }

    private record NearestRequest(float[] vector, List<Long> exclude, int limit) { }

    private final RestClient http;
    private final Map<Long, float[]> embeddings = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > 10_000;
        }
    });

    public Catalog(RestClient http) {
        this.http = http;
    }

    /** Empty if the title has no embedding yet (just added, not indexed). */
    public Optional<float[]> embedding(long titleId) {
        float[] cached = embeddings.get(titleId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            float[] v = http.get().uri("/titles/{id}/embedding", titleId).retrieve().body(float[].class);
            if (v != null) {
                embeddings.put(titleId, v);
            }
            return Optional.ofNullable(v);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public List<Title> nearest(float[] vector, List<Long> exclude, int limit) {
        return http.post().uri("/titles/nearest").body(new NearestRequest(vector, exclude, limit))
                .retrieve().body(new ParameterizedTypeReference<List<Title>>() { });
    }

    public List<Title> titles(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return http.get().uri(u -> u.path("/titles/batch").queryParam("ids", csv).build())
                .retrieve().body(new ParameterizedTypeReference<List<Title>>() { });
    }

    /** Test hook. */
    void forgetEmbeddings() {
        embeddings.clear();
    }
}
