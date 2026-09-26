CREATE TABLE IF NOT EXISTS benchmark_dataset_state (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    document_vectors_sha256 TEXT NOT NULL,
    document_count INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
