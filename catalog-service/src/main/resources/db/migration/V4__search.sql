-- Hybrid search (step 5): keyword search with Postgres full-text, meaning
-- search with pgvector. Each title gets a 384-dimension embedding of its
-- name, genres and description; the app fills it in (see Embeddings).
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE titles ADD COLUMN embedding vector(384);

-- Generated columns need immutable expressions; array_to_string isn't
-- declared immutable, so wrap it (safe here: text[] -> text never varies).
CREATE FUNCTION titles_document(name TEXT, genres TEXT[], description TEXT) RETURNS tsvector
    LANGUAGE sql IMMUTABLE AS $$
    SELECT setweight(to_tsvector('english', name), 'A')
        || setweight(to_tsvector('english', array_to_string(genres, ' ')), 'B')
        || setweight(to_tsvector('english', description), 'C')
$$;

ALTER TABLE titles ADD COLUMN document tsvector
    GENERATED ALWAYS AS (titles_document(name, genres, description)) STORED;

CREATE INDEX titles_document_idx ON titles USING GIN (document);
-- Approximate nearest neighbour index: HNSW, cosine distance.
CREATE INDEX titles_embedding_idx ON titles USING hnsw (embedding vector_cosine_ops);
