package dev.cloudbox;

import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(name = "cloudbox.role", havingValue = "coordinator", matchIfMissing = true)
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service = service; }

    public record FileInfo(UUID id, String filename, String contentType, long size,
                           String sha256, int chunks) {
        static FileInfo of(FileRepository.StoredFile f) {
            return new FileInfo(f.id(), f.filename(), f.contentType(), f.size(), f.sha256(), f.chunks().size());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileInfo> upload(@RequestParam("file") MultipartFile upload) throws IOException {
        String filename = Optional.ofNullable(upload.getOriginalFilename()).orElse("file");
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank()) filename = "file";
        String type = Optional.ofNullable(upload.getContentType()).orElse("application/octet-stream");
        FileRepository.StoredFile f;
        try (var stream = upload.getInputStream()) {
            f = service.upload(stream, filename, type);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(FileInfo.of(f));
    }

    @GetMapping
    public List<FileInfo> list() { return service.list().stream().map(FileInfo::of).toList(); }

    @GetMapping("/{id}")
    public FileInfo details(@PathVariable UUID id) { return FileInfo.of(service.metadata(id)); }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) throws IOException {
        var file = service.metadata(id);
        byte[] data = service.download(id);
        String safeName = file.filename().replaceAll("[\\r\\n\\\"]", "_");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .contentLength(data.length).body(data);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}