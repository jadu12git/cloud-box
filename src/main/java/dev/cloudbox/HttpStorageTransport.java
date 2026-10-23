package dev.cloudbox;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
public class HttpStorageTransport implements StorageTransport {
    private final RestClient client;

    public HttpStorageTransport() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void put(String node, String hash, byte[] data) {
        client.put().uri(node + "/internal/chunks/" + hash)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(data)
                .retrieve().toBodilessEntity();
    }

    @Override
    public byte[] get(String node, String hash) {
        return client.get().uri(node + "/internal/chunks/" + hash)
                .retrieve().body(byte[].class);
    }
}