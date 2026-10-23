package dev.cloudbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChunkStore {
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private final Path directory;

    public ChunkStore(CloudBoxProperties properties) throws IOException {
        directory = properties.dataDir.toAbsolutePath().normalize();
        if (properties.isStorage()) Files.createDirectories(directory);
    }

    private Path path(String hash) {
        if (!SHA.matcher(hash).matches()) throw new IllegalArgumentException("Invalid chunk checksum");
        return directory.resolve(hash.substring(0, 2)).resolve(hash);
    }

    public void put(String hash, byte[] bytes) throws IOException {
        if (!Hashing.sha256(bytes).equals(hash)) throw new IllegalArgumentException("Checksum mismatch");
        Path dest = path(hash);
        Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(dest.getParent(), "chunk-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public byte[] get(String hash) throws IOException {
        Path source = path(hash);
        if (!Files.exists(source)) throw new java.nio.file.NoSuchFileException(hash);
        byte[] bytes = Files.readAllBytes(source);
        if (!Hashing.sha256(bytes).equals(hash)) throw new CorruptChunkException(hash);
        return bytes;
    }

    public static class CorruptChunkException extends IOException {
        public CorruptChunkException(String hash) { super("Corrupt chunk: " + hash); }
    }
}