package com.streamhub.history;

import com.streamhub.events.PlaybackEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class HistoryStore {

    public record Progress(long titleId, int positionSeconds, int durationSeconds, Instant updatedAt) { }

    private final JdbcClient jdbc;

    public HistoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Applies one event, all or nothing. Returns false if this event id was
     * applied before (a redelivery), in which case nothing changes.
     */
    @Transactional
    public boolean apply(PlaybackEvent e) {
        if (e.titleId() <= 0 || e.userId() <= 0) {
            throw new IllegalArgumentException("invalid event " + e.eventId());
        }
        int fresh = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(e.eventId()).update();
        if (fresh == 0) {
            return false;
        }
        // Progress only moves forward in time: a late or repeated event can't rewind it.
        jdbc.sql("""
                INSERT INTO viewing_progress (user_id, title_id, position_seconds, duration_seconds, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, title_id) DO UPDATE
                   SET position_seconds = EXCLUDED.position_seconds,
                       duration_seconds = EXCLUDED.duration_seconds,
                       updated_at = EXCLUDED.updated_at
                 WHERE viewing_progress.updated_at < EXCLUDED.updated_at""")
                .params(e.userId(), e.titleId(), e.positionSeconds(), e.durationSeconds(),
                        Timestamp.from(e.occurredAt()))
                .update();
        // The non-idempotent part: safe only because of the guard above.
        if (e.watchedSeconds() > 0) {
            jdbc.sql("""
                    INSERT INTO watch_stats (user_id, watched_seconds) VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET watched_seconds = watch_stats.watched_seconds + EXCLUDED.watched_seconds""")
                    .params(e.userId(), e.watchedSeconds()).update();
        }
        return true;
    }

    /** Titles in progress, newest first, leaving out ones watched past {@code finishedAt}. */
    public List<Progress> continueWatching(long userId, double finishedAt, int limit) {
        return jdbc.sql("""
                SELECT title_id, position_seconds, duration_seconds, updated_at FROM viewing_progress
                 WHERE user_id = ? AND position_seconds > 0 AND position_seconds < duration_seconds * ?
                 ORDER BY updated_at DESC LIMIT ?""")
                .params(userId, finishedAt, limit)
                .query((rs, i) -> new Progress(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
    }

    public long watchedSeconds(long userId) {
        return jdbc.sql("SELECT watched_seconds FROM watch_stats WHERE user_id = ?").param(userId)
                .query(Long.class).optional().orElse(0L);
    }
}
