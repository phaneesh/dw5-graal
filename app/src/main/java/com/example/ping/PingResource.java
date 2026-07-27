package com.example.ping;

import java.io.IOException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /ping resource.
 *
 * <p>On each request the JSON response is {@linkplain PingQueue#enqueue(byte[]) enqueued} into the
 * bigqueue-backed async queue before being returned to the caller. The enqueue is synchronous and
 * non-blocking with respect to the response (bigqueue writes are mmap'd and fast); downstream
 * consumers drain the queue independently.
 */
@Path("/ping")
@Produces(MediaType.APPLICATION_JSON)
public class PingResource {

    private static final Logger log = LoggerFactory.getLogger(PingResource.class);

    private final PingQueue pingQueue;

    /**
     * HK2-injected constructor. HK2 resolves {@link PingQueue} from the binder registered in
     * {@link PingApplication}.
     *
     * @param pingQueue the async response queue; must not be {@code null}
     */
    @Inject
    public PingResource(PingQueue pingQueue) {
        if (pingQueue == null) {
            throw new IllegalArgumentException("pingQueue must not be null");
        }
        this.pingQueue = pingQueue;
    }

    @GET
    public PingResponse ping() {
        PingResponse response = PingResponse.now();
        byte[] message = serialize(response);
        try {
            pingQueue.enqueue(message);
        } catch (IOException e) {
            // The response is still returned to the caller; the queue failure is logged only.
            log.warn("Failed to enqueue ping response to bigqueue: {}", e.getMessage());
        }
        return response;
    }

    /**
     * Serialize the response to the same JSON the {@code /ping} endpoint returns.
     *
     * <p>Built manually to avoid pulling Jackson onto the hot path and to keep the bytes stable
     * for the queue consumer. Matches {@code PingResponse}'s record shape:
     * {@code {"pong":true,"serverTime":"<ISO8601>"}}.
     */
    private static byte[] serialize(PingResponse response) {
        return ("{\"pong\":true,\"serverTime\":\"" + response.serverTime() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
