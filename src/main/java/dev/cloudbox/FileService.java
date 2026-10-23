package dev.cloudbox;

import java.io.*;
import java.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "cloudbox.role", havingValue = "coordinator", matchIfMissing = true)
public class FileService {
    private final CloudBoxProperties config;
    private final FileRepository repository;
    private final StorageTransport transport;

    public FileService(CloudBoxProperties config, FileRepository repository, StorageTransport transport) {
        this.config = config;
        this.repository = repository;
        this.transport = transport;
    }

    public FileRepository.StoredFile upload(InputStream input, String filename, String contentType) throws IOException {
        UUID id = UUID.randomUUID();
        List<FileRepository.Chunk> chunks = new ArrayList<>();
        long length = 0;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        int index = 0;
        byte[] data;
        while ((data = input.readNBytes(config.chunkSize)).length > 0) {
            digest.update(data);
            length += data.length;
            String hash = Hashing.sha256(data);
            List<String> storedAt = replicate(index, hash, data);
            chunks.add(new FileRepository.Chunk(index++, hash, data.length, storedAt));
        }
        var file = new FileRepository.StoredFile(id, filename, contentType, length,
                HexFormat.of().formatHex(digest.digest()), List.copyOf(chunks));
        repository.save(file);
        return file;
    }

    // Deterministic round-robin placement with fallback to additional healthy nodes.
    private List<String> replicate(int index, String hash, byte[] data) throws IOException {
        List<String> successful = new ArrayList<>();
        int count = config.nodes.size();
        for (int i = 0; i < count && successful.size() < config.replicas; i++) {
            String node = config.nodes.get((index + i) % count);
            try { transport.put(node, hash, data); successful.add(node); }
            catch (RuntimeException e) { /* attempt another configured node */ }
        }
        if (successful.size() < config.replicas) {
            throw new IOException("Insufficient healthy nodes: required " + config.replicas
                    + ", available " + successful.size());
        }
        return List.copyOf(successful);
    }

    public FileRepository.StoredFile metadata(UUID id) {
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("File not found"));
    }

    public List<FileRepository.StoredFile> list() {
        return repository.list().stream().map(f -> metadata(f.id())).toList();
    }

    public byte[] download(UUID id) throws IOException {
        var file = metadata(id);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (var chunk : file.chunks()) {
            byte[] valid = null;
            for (String node : chunk.nodes()) {
                try {
                    byte[] candidate = transport.get(node, chunk.sha256());
                    if (candidate != null && candidate.length == chunk.size()
                            && Hashing.sha256(candidate).equals(chunk.sha256())) {
                        valid = candidate;
                        break;
                    }
                } catch (RuntimeException ignored) { /* Try the next replica. */ }
            }
            if (valid == null) throw new IOException("No valid replica for chunk " + chunk.ordinal());
            output.write(valid);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length != file.size() || !Hashing.sha256(bytes).equals(file.sha256())) {
            throw new IOException("Whole-file checksum verification failed");
        }
        return bytes;
    }

    public boolean delete(UUID id) { return repository.delete(id); }
}