package com.example.ping;

import java.io.IOException;

import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;

public class PingApplication extends Application<PingConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(PingApplication.class);

    public static void main(String[] args) throws Exception {
        new PingApplication().run(args);
    }

    @Override
    public String getName() {
        return "dw5-graal";
    }

    @Override
    public void initialize(Bootstrap<PingConfiguration> bootstrap) {
        // no-op
    }

    @Override
    public void run(PingConfiguration configuration, Environment environment) throws Exception {
        // Async response queue (bigqueue-backed, temp dir under /tmp). Registered as Managed so the
        // underlying mmap'd pages are flushed + closed on shutdown.
        PingQueue pingQueue = createPingQueue();
        environment.lifecycle().manage(pingQueue);

        // Register the CLASS, not an instance. DropwizardResourceConfig.register(Object) generates
        // a Javassist proxy subclass with a random-UUID name (SpecificBinder<uuid>) at runtime, which
        // is fundamentally incompatible with GraalVM native-image's closed-world assumption.
        // register(Class) delegates straight to Jersey's ResourceConfig.register(Class) — no proxy.
        //
        // PingResource takes PingQueue via constructor injection; the binder below binds the
        // concrete PingQueue instance to its type so HK2 resolves it.
        environment.jersey().register(PingResource.class);

        // HK2 binder: make PingQueue injectable for PingResource's constructor.
        environment.jersey().register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(pingQueue).to(PingQueue.class);
            }
        });
    }

    /**
     * Create the bigqueue-backed response queue in a fresh temp directory under {@code /tmp}.
     *
     * @return a started, empty {@link PingQueue}
     * @throws IOException if the temp dir or underlying bigqueue cannot be created
     */
    private static PingQueue createPingQueue() throws IOException {
        PingQueue queue = new PingQueue();
        log.info("ping response queue at {}", queue.getQueueDir());
        return queue;
    }
}
