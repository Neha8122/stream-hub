package com.streamhub.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Titles in Postgres. Counts its own reads, so tests can prove the cache
 * keeps load off the database.
 */
@Repository
public class TitleRepository {

    private final JdbcClient jdbc;
    private final AtomicLong reads = new AtomicLong();

    public TitleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Title> findById(long id) {
        reads.incrementAndGet();
        return jdbc.sql("SELECT * FROM titles WHERE id = ?").param(id).query(TitleRepository::map).optional();
    }

    public List<Title> findByGenre(String genre, int limit) {
        reads.incrementAndGet();
        return jdbc.sql("SELECT * FROM titles WHERE ? = ANY(genres) ORDER BY release_year DESC, id LIMIT ?")
                .params(genre, limit).query(TitleRepository::map).list();
    }

    public long insert(Title t) {
        return jdbc.sql("""
                INSERT INTO titles (name, genres, release_year, duration_minutes, description)
                VALUES (?, ?::text[], ?, ?, ?) RETURNING id""")
                .params(t.name(), pgArray(t.genres()), t.releaseYear(), t.durationMinutes(), t.description())
                .query(Long.class).single();
    }

    /** True if the title existed. */
    public boolean update(Title t) {
        return jdbc.sql("""
                UPDATE titles SET name = ?, genres = ?::text[], release_year = ?, duration_minutes = ?,
                       description = ?, updated_at = now()
                WHERE id = ?""")
                .params(t.name(), pgArray(t.genres()), t.releaseYear(), t.durationMinutes(), t.description(), t.id())
                .update() == 1;
    }

    /** Database reads so far (for tests and metrics). */
    public long reads() {
        return reads.get();
    }

    private static Title map(ResultSet rs, int row) throws SQLException {
        Array genres = rs.getArray("genres");
        return new Title(rs.getLong("id"), rs.getString("name"), List.of((String[]) genres.getArray()),
                rs.getInt("release_year"), rs.getInt("duration_minutes"), rs.getString("description"));
    }

    private static String pgArray(List<String> values) {
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\"", "\\\"") + "\"").toList()) + "}";
    }
}
