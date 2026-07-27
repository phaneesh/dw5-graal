package com.example.ping;

import java.time.Instant;

/** JSON response for /ping. Jackson serializes records natively on Dropwizard 5. */
public record PingResponse(boolean pong, String serverTime) {
    public static PingResponse now() {
        return new PingResponse(true, Instant.now().toString());
    }
}
