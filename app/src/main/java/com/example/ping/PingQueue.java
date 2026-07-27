package com.example.ping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

import io.dropwizard.lifecycle.Managed;

/**
 * Async persistent queue for /ping responses, backed by bigqueue ({@link BigQueueImpl}).
 *
 * <p>bigqueue is a memory-mapped, file-based persistent queue. The queue directory is created
 * as a fresh temp directory under {@code /tmp} so each app run gets an isolated queue. The queue
 * is registered as a Dropwizard {@link Managed} so it is closed cleanly on shutdown.
 *
 * <p>The /ping handler {@linkplain #enqueue enqueues} the JSON response bytes before returning
 * them to the caller. Downstream consumers can {@link IBigQueue#dequeue() dequeue} async.
 */
public class PingQueue implements Managed {

    /** Subdirectory of /tmp holding this run's bigqueue data files. */
    private final Path queueDir;
    private final IBigQueue queue;

    /**
     * Create a fresh queue in a new temp directory under {@code /tmp}.
     *
     * @throws IOException if the temp directory or the underlying bigqueue cannot be created
     */
    public PingQueue() throws IOException {
        this.queueDir = Files.createTempDirectory(Path.of("/tmp"), "dw5-graal-pingqueue-");
        // bigqueue creates its data files under queueDir/<queueName>/.
        this.queue = new BigQueueImpl(queueDir.toString(), "ping-responses");
    }

    /**
     * Enqueue the response bytes for async downstream processing.
     *
     * @param message JSON response bytes; must not be {@code null}
     * @throws IOException if the enqueue fails (disk full, mmap error, etc.)
     */
    public void enqueue(byte[] message) throws IOException {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        queue.enqueue(message);
    }

    /** @return the backing bigqueue (for consumers / inspection) */
    public IBigQueue getQueue() {
        return queue;
    }

    /** @return the temp directory holding this run's queue data files */
    public Path getQueueDir() {
        return queueDir;
    }

    @Override
    public void start() throws Exception {
        // bigqueue is ready to use after construction; nothing to do at start.
    }

    @Override
    public void stop() throws Exception {
        // flush + close the underlying mmap'd pages, then clean up the temp dir.
        try {
            queue.flush();
        } finally {
            queue.close();
        }
    }
}
