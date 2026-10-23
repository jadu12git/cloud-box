package dev.cloudbox;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkStoreTest {
    @TempDir Path temp;
    private ChunkStore store() throws Exception {
        return new ChunkStore(new CloudBoxProperties("storage", temp.toString(),
                "http://node1,http://node2", 2, 8));
    }
    @Test void storesDuplicateContentIdempotently() throws Exception {
        var store = store();
        byte[] data = "hello world".getBytes();
        String hash = Hashing.sha256(data);
        store.put(hash, data);
        store.put(hash, data);
        assertArrayEquals(data, store.get(hash));
    }
    @Test void rejectsMismatchedContent() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> store().put(Hashing.sha256("a".getBytes()), "b".getBytes()));
    }
    @Test void detectsCorruptedChunk() throws Exception {
        var store = store();
        byte[] bytes = "valid".getBytes();
        String hash = Hashing.sha256(bytes);
        store.put(hash, bytes);
        Files.write(temp.resolve(hash.substring(0, 2)).resolve(hash), "corrupt".getBytes());
        assertThrows(ChunkStore.CorruptChunkException.class, () -> store.get(hash));
    }
}