package dev.cloudbox;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/chunks")
@ConditionalOnProperty(name = "cloudbox.role", havingValue = "storage")
public class StorageController {
    private final ChunkStore store;
    public StorageController(ChunkStore store) { this.store = store; }

    @PutMapping(value = "/{hash}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> put(@PathVariable String hash, @RequestBody byte[] bytes) throws IOException {
        store.put(hash, bytes);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{hash}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] get(@PathVariable String hash) throws IOException { return store.get(hash); }
}