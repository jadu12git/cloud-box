# CloudBox ☁️

**A replicated, chunk-based distributed file storage service built in Java, Spring Boot, PostgreSQL, Docker, and JUnit.**

CloudBox splits uploaded files into 1 MiB chunks, hashes each chunk with SHA-256, distributes copies across storage nodes, indexes file metadata and replica locations in PostgreSQL, and reconstructs downloads with checksum verification and replica failover.

## Architecture

```text
Client --> Coordinator (Spring Boot :8080) --> PostgreSQL (metadata)
                     |
                     +--> Storage Node 1 (persistent volume)
                     +--> Storage Node 2 (persistent volume)
                     +--> Storage Node 3 (persistent volume)
```

- **Coordinator:** REST API, chunk splitting, checksums, round-robin placement, replication and reads
- **Nodes:** content-addressed chunk persistence with checksum verification on write and read
- **PostgreSQL:** files, ordered chunks and recorded node placements
- **Replication factor:** two distinct nodes per chunk (configurable)
- **Failure behavior:** writes try spare nodes when one is offline; downloads use another healthy replica. Uploads fail rather than silently reducing the replication factor.

## Run locally (Docker required)

```bash
git clone https://github.com/jadu12git/cloud-box.git
cd cloud-box
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

Upload, inspect, download, and delete:

```bash
curl -F "file=@example.pdf" http://localhost:8080/api/files
curl http://localhost:8080/api/files
curl http://localhost:8080/api/files/REPLACE_WITH_ID
curl -o restored.pdf http://localhost:8080/api/files/REPLACE_WITH_ID/download
curl -X DELETE http://localhost:8080/api/files/REPLACE_WITH_ID
```

Simulate a node failure and verify an already-uploaded file can be downloaded:

```bash
docker compose stop node1
curl -o restored-after-failure.pdf http://localhost:8080/api/files/REPLACE_WITH_ID/download
docker compose start node1
```

Run tests (JDK 21 + Maven required):

```bash
mvn test
```

Reset development data: `docker compose down -v` (**deletes stored files and metadata**).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/files` | Multipart upload under field `file` |
| GET | `/api/files` | List metadata |
| GET | `/api/files/{id}` | View metadata |
| GET | `/api/files/{id}/download` | Download checked, reassembled content |
| DELETE | `/api/files/{id}` | Delete metadata (physical chunks intentionally retained) |
| GET | `/actuator/health` | Health endpoint |
| PUT/GET | `/internal/chunks/{sha256}` | Internal node-only chunk API |

## Design decisions and limitations

- Node URLs are configured statically. Failed nodes are skipped; **automatic repair/rebalancing is not implemented**.
- Chunks are content-addressed by SHA-256: repeated bytes reuse the same path, and invalid data is rejected. Metadata identifies each upload separately.
- Deletes remove metadata, **not physical chunk objects**. Garbage collection and reference counting are future work.
- An interrupted upload can leave orphaned chunks on disk; metadata is published only after all chunks meet the required replication count.
- Download assembly is in memory and upload size defaults to a 100 MB limit. For production, implement streaming and upload quotas.
- Internal endpoints assume an isolated, trusted network. Add authentication, TLS, rate limiting, and credential management before public internet deployment.
- This repository is **deployment-ready for a local Docker environment**, not a claimed production-hardened cloud storage platform.

## Test coverage

JUnit 5 tests cover chunk checksums and idempotent duplicate storage, multi-chunk upload and retrieval, failed-node write fallback, failed-node read fallback, corrupted-replica fallback, and insufficient-replica upload failure. Run `mvn test` to verify.

## Tech stack

Java 21 · Spring Boot · PostgreSQL 16 · Docker Compose · Maven · JUnit 5

## Author

[Joseph Adu](https://github.com/jadu12git)
