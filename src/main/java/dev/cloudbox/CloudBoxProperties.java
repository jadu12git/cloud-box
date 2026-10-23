package dev.cloudbox;

import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CloudBoxProperties {
    public final String role;
    public final Path dataDir;
    public final List<String> nodes;
    public final int replicas;
    public final int chunkSize;

    public CloudBoxProperties(@Value("${cloudbox.role}") String role,
            @Value("${cloudbox.data-dir}") String dataDir,
            @Value("${cloudbox.nodes}") String nodes,
            @Value("${cloudbox.replicas}") int replicas,
            @Value("${cloudbox.chunk-size}") int chunkSize) {
        this.role = role;
        this.dataDir = Path.of(dataDir);
        this.nodes = List.of(nodes.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.replicas = replicas;
        this.chunkSize = chunkSize;
        if (chunkSize < 1 || replicas < 1 || replicas > this.nodes.size()) {
            throw new IllegalArgumentException("Invalid chunk size or replication factor");
        }
    }

    public boolean isStorage() { return role.equalsIgnoreCase("storage"); }
    public boolean isCoordinator() { return role.equalsIgnoreCase("coordinator"); }
}