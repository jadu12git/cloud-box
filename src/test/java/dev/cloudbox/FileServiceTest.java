package dev.cloudbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileServiceTest {
    FileRepository repository;
    FakeTransport transport;
    FileService service;

    static class FakeTransport implements StorageTransport {
        final Map<String, byte[]> chunks = new HashMap<>();
        final Set<String> down = new HashSet<>();
        private String key(String node, String hash) { return node + "/" + hash; }
        public void put(String node, String hash, byte[] data) {
            if (down.contains(node)) throw new IllegalStateException("node unavailable");
            chunks.put(key(node, hash), data.clone());
        }
        public byte[] get(String node, String hash) {
            if (down.contains(node)) throw new IllegalStateException("node unavailable");
            byte[] data = chunks.get(key(node, hash));
            if (data == null) throw new IllegalStateException("missing");
            return data;
        }
    }

    @BeforeEach void setup() {
        repository = mock(FileRepository.class);
        transport = new FakeTransport();
        var properties = new CloudBoxProperties("coordinator", "data", "n1,n2,n3", 2, 4);
        service = new FileService(properties, repository, transport);
        doAnswer(inv -> {
            var file = (FileRepository.StoredFile) inv.getArgument(0);
            when(repository.find(file.id())).thenReturn(Optional.of(file));
            return null;
        }).when(repository).save(any());
    }

    @Test void uploadsAndReassemblesMultipleChunks() throws Exception {
        byte[] original = "hello-distributed-cloudbox".getBytes();
        var file = service.upload(new ByteArrayInputStream(original), "test.txt", "text/plain");
        assertTrue(file.chunks().size() > 1);
        assertTrue(file.chunks().stream().allMatch(chunk -> chunk.nodes().size() == 2));
        assertArrayEquals(original, service.download(file.id()));
        verify(repository).save(any());
    }

    @Test void storesDuplicateUploadsWithDistinctIds() throws Exception {
        byte[] bytes = "same file".getBytes();
        var a = service.upload(new ByteArrayInputStream(bytes), "a.txt", "text/plain");
        var b = service.upload(new ByteArrayInputStream(bytes), "b.txt", "text/plain");
        assertNotEquals(a.id(), b.id());
        assertEquals(a.sha256(), b.sha256());
        assertArrayEquals(bytes, service.download(b.id()));
    }

    @Test void retriesOtherNodeDuringUpload() throws Exception {
        transport.down.add("n1");
        var file = service.upload(new ByteArrayInputStream("test".getBytes()), "x", "text/plain");
        assertEquals(List.of("n2", "n3"), file.chunks().get(0).nodes());
    }

    @Test void downloadsAfterStoredNodeFails() throws Exception {
        byte[] bytes = "resilient storage".getBytes();
        var file = service.upload(new ByteArrayInputStream(bytes), "x", "text/plain");
        transport.down.add("n1");
        assertArrayEquals(bytes, service.download(file.id()));
    }

    @Test void fallsBackWhenReplicaIsCorrupted() throws Exception {
        byte[] bytes = "abcdefgh".getBytes();
        var file = service.upload(new ByteArrayInputStream(bytes), "x", "text/plain");
        var first = file.chunks().get(0);
        transport.chunks.put(first.nodes().get(0) + "/" + first.sha256(), "xxxx".getBytes());
        assertArrayEquals(bytes, service.download(file.id()));
    }

    @Test void rejectsUploadIfReplicationFactorCannotBeMet() {
        transport.down.add("n1");
        transport.down.add("n2");
        assertThrows(IOException.class,
                () -> service.upload(new ByteArrayInputStream("data".getBytes()), "x", "text/plain"));
        verify(repository, never()).save(any());
    }

    @Test void rejectsDownloadWhenAllReplicasCorrupted() throws Exception {
        var file = service.upload(new ByteArrayInputStream("data".getBytes()), "x", "text/plain");
        var chunk = file.chunks().get(0);
        for (String node : chunk.nodes()) transport.chunks.put(node + "/" + chunk.sha256(), "bad".getBytes());
        assertThrows(IOException.class, () -> service.download(file.id()));
    }

    @Test void emptyFileCanRoundTrip() throws Exception {
        var file = service.upload(new ByteArrayInputStream(new byte[0]), "empty", "application/octet-stream");
        assertEquals(0, file.chunks().size());
        assertArrayEquals(new byte[0], service.download(file.id()));
    }
}