-- Every event id this consumer has applied: the guard against redelivery.
CREATE TABLE processed_events (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE viewing_progress (
    user_id          BIGINT      NOT NULL,
    title_id         BIGINT      NOT NULL,
    position_seconds INT         NOT NULL,
    duration_seconds INT         NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,     -- the event's time, not the write's
    PRIMARY KEY (user_id, title_id)
);
CREATE INDEX viewing_progress_recent_idx ON viewing_progress (user_id, updated_at DESC);

CREATE TABLE watch_stats (
    user_id         BIGINT PRIMARY KEY,
    watched_seconds BIGINT NOT NULL
);
