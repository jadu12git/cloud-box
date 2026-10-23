package dev.cloudbox;

public interface StorageTransport {
    void put(String node, String hash, byte[] data);
    byte[] get(String node, String hash);
}