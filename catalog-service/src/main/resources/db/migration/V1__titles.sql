CREATE TABLE titles (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL,
    genres           TEXT[]      NOT NULL,
    release_year     INT         NOT NULL,
    duration_minutes INT         NOT NULL,
    description      TEXT        NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX titles_genres_idx ON titles USING GIN (genres);
