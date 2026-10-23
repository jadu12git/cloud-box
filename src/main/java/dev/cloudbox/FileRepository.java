package dev.cloudbox;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FileRepository {
    private final JdbcTemplate jdbc;
    public FileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Chunk(int ordinal, String sha256, int size, List<String> nodes) {}
    public record StoredFile(UUID id, String filename, String contentType, long size,
                             String sha256, List<Chunk> chunks) {}

    @Transactional
    public void save(StoredFile file) {
        jdbc.update("INSERT INTO files(id, filename, content_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?)",
                file.id(), file.filename(), file.contentType(), file.size(), file.sha256());
        for (Chunk chunk : file.chunks()) {
            jdbc.update("INSERT INTO chunks(file_id, ordinal, sha256, size_bytes) VALUES (?, ?, ?, ?)",
                    file.id(), chunk.ordinal(), chunk.sha256(), chunk.size());
            for (String node : chunk.nodes()) {
                jdbc.update("INSERT INTO replicas(file_id, ordinal, node_url) VALUES (?, ?, ?)",
                        file.id(), chunk.ordinal(), node);
            }
        }
    }

    public Optional<StoredFile> find(UUID id) {
        var rows = jdbc.query("SELECT * FROM files WHERE id = ?",
                (rs, n) -> new StoredFile((UUID)rs.getObject("id"), rs.getString("filename"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getString("sha256"), List.of()), id);
        if (rows.isEmpty()) return Optional.empty();
        var file = rows.get(0);
        var chunks = jdbc.query("SELECT * FROM chunks WHERE file_id = ? ORDER BY ordinal",
                (rs, n) -> new Chunk(rs.getInt("ordinal"), rs.getString("sha256"),
                        rs.getInt("size_bytes"),
                        jdbc.queryForList("SELECT node_url FROM replicas WHERE file_id = ? AND ordinal = ? ORDER BY node_url",
                                String.class, id, rs.getInt("ordinal"))), id);
        return Optional.of(new StoredFile(file.id(), file.filename(), file.contentType(),
                file.size(), file.sha256(), chunks));
    }

    public List<StoredFile> list() {
        return jdbc.query("SELECT * FROM files ORDER BY created_at DESC",
                (rs, n) -> new StoredFile((UUID)rs.getObject("id"), rs.getString("filename"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getString("sha256"), List.of()));
    }

    @Transactional
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM files WHERE id = ?", id) == 1;
    }
}