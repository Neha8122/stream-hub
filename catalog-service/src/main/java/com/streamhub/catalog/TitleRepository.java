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

    /** Not SELECT *: the embedding (384 floats) and search document stay in the database. */
    private static final String COLUMNS = "id, name, genres, release_year, duration_minutes, description";

    public Optional<Title> findById(long id) {
        reads.incrementAndGet();
        return jdbc.sql("SELECT " + COLUMNS + " FROM titles WHERE id = ?").param(id).query(TitleRepository::map).optional();
    }

    public List<Title> findByGenre(String genre, int limit) {
        reads.incrementAndGet();
        return jdbc.sql("SELECT " + COLUMNS + " FROM titles WHERE ? = ANY(genres) ORDER BY release_year DESC, id LIMIT ?")
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
                       description = ?, updated_at = now(), embedding = NULL   -- re-embedded by EmbeddingIndexer
                WHERE id = ?""")
                .params(t.name(), pgArray(t.genres()), t.releaseYear(), t.durationMinutes(), t.description(), t.id())
                .update() == 1;
    }

    // --- search (step 5) ---

    /**
     * Ids by keyword relevance. Any word may match (OR, not AND): "funny
     * space adventure" still finds a title that is only a space adventure;
     * titles matching more words, or in the name, rank higher.
     */
    public List<Long> keywordSearch(String query, int limit) {
        return jdbc.sql("""
                WITH q AS (SELECT NULLIF(replace(plainto_tsquery('english', ?)::text, '&', '|'), '')::tsquery AS q)
                SELECT id FROM titles, q
                WHERE q.q IS NOT NULL AND document @@ q.q
                ORDER BY ts_rank_cd(document, q.q) DESC, id
                LIMIT ?""")
                .params(query, limit).query(Long.class).list();
    }

    /** Ids nearest in meaning (cosine distance, HNSW index), skipping {@code exclude}. */
    public List<Long> nearest(float[] vector, int limit, List<Long> exclude) {
        return jdbc.sql("""
                SELECT id FROM titles
                WHERE embedding IS NOT NULL AND NOT (id = ANY(?::bigint[]))
                ORDER BY embedding <=> ?::vector
                LIMIT ?""")
                .params("{" + String.join(",", exclude.stream().map(String::valueOf).toList()) + "}",
                        Embedder.literal(vector), limit)
                .query(Long.class).list();
    }

    public Optional<float[]> embedding(long id) {
        return jdbc.sql("SELECT embedding::text FROM titles WHERE id = ? AND embedding IS NOT NULL").param(id)
                .query(String.class).optional().map(Embedder::parse);
    }

    public List<Title> missingEmbeddings(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM titles WHERE embedding IS NULL ORDER BY id LIMIT ?")
                .param(limit).query(TitleRepository::map).list();
    }

    public void saveEmbedding(long id, float[] vector) {
        jdbc.sql("UPDATE titles SET embedding = ?::vector WHERE id = ?").params(Embedder.literal(vector), id).update();
    }

    public long countMissingEmbeddings() {
        return jdbc.sql("SELECT count(*) FROM titles WHERE embedding IS NULL").query(Long.class).single();
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
