package com.example.ping;

import com.fasterxml.jackson.annotation.JsonTypeName;

import org.eclipse.jetty.server.RequestLog;
import io.dropwizard.request.logging.RequestLogFactory;

/**
 * A no-op request log factory that disables Dropwizard's per-request access logging.
 *
 * <p>Dropwizard's default request log formats and writes a log line for every HTTP
 * request, which is a significant per-request CPU cost under benchmark load. The
 * built-in factories ({@code LogbackAccessRequestLogFactory}, {@code ExternalRequestLogFactory})
 * both construct a real request log; {@code ExternalRequestLogFactory} additionally fails
 * on native-image (reflection on a Jetty method not in the reachability metadata).
 *
 * <p>This factory returns {@code null} from {@link #build} so {@code DefaultServerFactory}
 * skips attaching any request log, eliminating the per-request logging overhead entirely.
 *
 * <p>Register in {@code application.yml} with {@code server.requestLog.type: noop}.
 */
@JsonTypeName("noop")
public class NoopRequestLogFactory implements RequestLogFactory<RequestLog> {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public RequestLog build(String name) {
        // Returning null tells DefaultServerFactory not to attach a request log.
        return null;
    }
}
