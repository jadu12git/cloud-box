#!/usr/bin/env python3
"""Live integration suite against Docker Compose + real PostgreSQL + storage nodes.

Usage: docker compose up -d --build && python3 tests/integration_test.py
Requires only Python 3 stdlib and Docker Compose. Does not delete volumes.
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE = os.getenv("CLOUDBOX_URL", "http://localhost:8080").rstrip("/")
NODE = os.getenv("CLOUDBOX_FAILURE_NODE", "node1")


def compose(*args):
    return subprocess.run(["docker", "compose", *args], check=True, capture_output=True, text=True)


def request(method, path, body=None, content_type=None):
    headers = {}
    if content_type:
        headers["Content-Type"] = content_type
    req = Request(BASE + path, data=body, headers=headers, method=method)
    with urlopen(req, timeout=30) as response:
        return response.status, response.read()


def upload(name, data):
    boundary = "cloudbox-" + uuid.uuid4().hex
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    body = prefix + data + f"\r\n--{boundary}--\r\n".encode()
    status, response = request("POST", "/api/files", body, f"multipart/form-data; boundary={boundary}")
    assert status == 201, f"upload returned {status}"
    return json.loads(response)


def download(file_id):
    status, data = request("GET", f"/api/files/{file_id}/download")
    assert status == 200
    return data


def wait_healthy(seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            _, raw = request("GET", "/actuator/health")
            if json.loads(raw).get("status") == "UP":
                return
        except (URLError, HTTPError, TimeoutError, OSError, ValueError):
            pass
        time.sleep(2)
    raise AssertionError("Coordinator did not become healthy")


def check(label, function):
    function()
    print(f"PASS {label}", flush=True)


def main():
    print("CloudBox live integration: real API, PostgreSQL, and Docker storage nodes", flush=True)
    wait_healthy()
    payload = bytes(range(256)) * 9000  # > 2 MiB; crosses three 1-MiB chunks
    name = f"integration-{uuid.uuid4().hex}.bin"
    file = upload(name, payload)
    file_id = file["id"]
    ids = [file_id]
    stopped_node = False
    stopped_database = False

    try:
        assert file["size"] == len(payload)
        assert file["sha256"] == hashlib.sha256(payload).hexdigest()
        assert file["chunks"] >= 3
        print("PASS multi-chunk upload and full-file SHA-256")

        check("download matches byte-for-byte", lambda: assert_bytes(download(file_id), payload))
        check("metadata persisted in PostgreSQL", lambda: assert_metadata(file_id, name, len(payload)))

        other = upload(name, payload)
        ids.append(other["id"])
        assert other["id"] != file_id and other["sha256"] == file["sha256"]
        check("duplicate content creates distinct file IDs", lambda: assert_bytes(download(other["id"]), payload))

        empty = upload(f"empty-{uuid.uuid4().hex}.dat", b"")
        ids.append(empty["id"])
        check("empty file round-trip", lambda: assert_bytes(download(empty["id"]), b""))

        # Gracefully stop exactly one node; always bring it back in finally.
        compose("stop", NODE)
        stopped_node = True
        check("download survives one storage-node failure", lambda: assert_bytes(download(file_id), payload))
        compose("start", NODE)
        stopped_node = False

        # Restart only PostgreSQL. The connection pool should reconnect.
        compose("restart", "postgres")
        stopped_database = True
        deadline = time.monotonic() + 90
        while True:
            try:
                assert_metadata(file_id, name, len(payload))
                assert_bytes(download(file_id), payload)
                break
            except (AssertionError, HTTPError, URLError, OSError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(2)
        stopped_database = False
        print("PASS metadata and download survive PostgreSQL restart")

        # Restart the coordinator; permanent named node volumes remain untouched.
        compose("restart", "coordinator")
        wait_healthy()
        check("file persists after coordinator restart", lambda: assert_bytes(download(file_id), payload))

        print("\nALL LIVE INTEGRATION TESTS PASSED", flush=True)
    finally:
        if stopped_node:
            compose("start", NODE)
        if stopped_database:
            # Restart only returns after container start; let health recover.
            wait_healthy()
        # Clean up test metadata; physical orphaned chunks remain by current design.
        for id in ids:
            try:
                request("DELETE", f"/api/files/{id}")
            except (HTTPError, URLError, OSError):
                print(f"WARN unable to remove test metadata for {id}", file=sys.stderr)


def assert_bytes(actual, expected):
    assert actual == expected, f"download mismatch: expected {len(expected)} bytes, received {len(actual)}"


def assert_metadata(id, name, size):
    status, body = request("GET", f"/api/files/{id}")
    metadata = json.loads(body)
    assert status == 200 and metadata["id"] == id
    assert metadata["filename"] == name and metadata["size"] == size
    _, listing = request("GET", "/api/files")
    assert any(record["id"] == id for record in json.loads(listing))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError, HTTPError, URLError, OSError) as exc:
        print(f"FAIL integration test: {exc}", file=sys.stderr)
        sys.exit(1)
