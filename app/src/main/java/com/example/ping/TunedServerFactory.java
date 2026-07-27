package com.example.ping;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.server.DefaultServerFactory;
import io.dropwizard.metrics.jetty12.InstrumentedQueuedThreadPool;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

/**
 * Dropwizard 5's {@link DefaultServerFactory} couples the carrier thread pool cap
 * ({@code maxThreads}) to the virtual-thread concurrency limit
 * ({@code new VirtualThreadPool(maxThreads)}). On a 4-core box we want few carriers
 * (~8) but a high VT concurrency cap (thousands). This factory overrides
 * {@link #createThreadPool} to decouple them: carriers use {@code maxThreads/minThreads},
 * the VT executor uses a separate {@link #virtualThreadsMax} cap.
 *
 * <p>Only the <em>application</em> pool is decoupled. The admin pool is built in a
 * private method and still couples {@code adminMaxThreads} to its VT cap — acceptable
 * because admin traffic (healthchecks) is negligible.
 */
@JsonTypeName("custom")
public class TunedServerFactory extends DefaultServerFactory {

    /** Virtual-thread concurrency cap for the application pool. */
    private int virtualThreadsMax = 10_000;

    public int getVirtualThreadsMax() {
        return virtualThreadsMax;
    }

    public void setVirtualThreadsMax(int virtualThreadsMax) {
        this.virtualThreadsMax = virtualThreadsMax;
    }

    @Override
    protected ThreadPool createThreadPool(MetricRegistry metricRegistry) {
        // Carrier pool: platform threads bounded by maxThreads/minThreads (low, ~cores).
        var queue = new BlockingArrayQueue<Runnable>(getMinThreads(), getMaxThreads());
        var threadPool = new InstrumentedQueuedThreadPool(
                metricRegistry,
                getMaxThreads(),
                getMinThreads(),
                (int) getIdleThreadTimeout().toMilliseconds(),
                queue);

        // VT executor: separate, high cap — decoupled from carrier maxThreads.
        if (isEnableVirtualThreads()) {
            var virtualExecutor = new VirtualThreadPool(virtualThreadsMax);
            virtualExecutor.setName("dw-virtual");
            threadPool.setVirtualThreadsExecutor(virtualExecutor);
        }
        threadPool.setName("dw");
        return threadPool;
    }
}
