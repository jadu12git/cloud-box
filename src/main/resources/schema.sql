CREATE TABLE IF NOT EXISTS files (
 id UUID PRIMARY KEY,
 filename TEXT NOT NULL,
 content_type TEXT NOT NULL,
 size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
 sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_files_sha256 ON files(sha256);
CREATE TABLE IF NOT EXISTS chunks (
 file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
 ordinal INT NOT NULL CHECK (ordinal >= 0),
 sha256 CHAR(64) NOT NULL,
 size_bytes INT NOT NULL CHECK (size_bytes >= 0),
 PRIMARY KEY (file_id, ordinal)
);
CREATE TABLE IF NOT EXISTS replicas (
 file_id UUID NOT NULL,
 ordinal INT NOT NULL,
 node_url TEXT NOT NULL,
 PRIMARY KEY (file_id, ordinal, node_url),
 FOREIGN KEY (file_id, ordinal) REFERENCES chunks(file_id, ordinal) ON DELETE CASCADE
);