package com.streamhub.catalog;

import java.util.List;

/** One film or series in the catalog. */
public record Title(long id, String name, List<String> genres, int releaseYear,
                    int durationMinutes, String description) {
}
