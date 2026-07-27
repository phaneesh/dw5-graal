package com.example.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark for the dw5-graal /ping endpoint.
 *
 * <h2>Design — external-process harness, not JMH @Fork</h2>
 *
 * JMH {@code @Fork} only forks the <em>benchmark</em> JVM (the one running the
 * {@code @Benchmark} method). It cannot launch a GraalVM native binary. So both modes
 * use an external-process harness: {@code @Setup(Level.Trial)} starts the target app as
 * an OS process, polls {@code /healthcheck} until it answers 200, and {@code @TearDown}
 * destroys it. The benchmark JVM (forked by JMH) only runs the HTTP client load generator.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@code mode=jvm} — starts {@code java -jar app/target/dw5-graal-app.jar server application.yml}</li>
 *   <li>{@code mode=native} — starts {@code app/target/dw5-graal-app server application.yml}</li>
 * </ul>
 *
 * <h3>Benchmark modes</h3>
 * A single {@code @Benchmark} method carries both {@link Mode#Throughput} and
 * {@link Mode#SampleTime} via {@link BenchmarkMode}. Run with {@code -bm thrpt} or
 * {@code -bm sample} on the JMH CLI to select. {@code Mode.SampleTime} reports p99.
 *
 * <h2>Running</h2>
 * <pre>
 *   java -jar benchmarks/target/benchmarks.jar \
 *        -rf json -rff benchmarks/target/jmh-result.json \
 *        -prof gc \
 *        -p mode=native \
 *        -bm thrpt -wi 3 -i 5 -f 1 \
 *        PingBenchmark
 * </pre>
 *
 * Heap for the benchmark JVM is controlled by the JMH {@code -jvm} / {@code -jvmArgs} flags,
 * NOT here. The target app heap is fixed: native binary baked with {@code -R:MaxHeapSize=1g};
 * JVM-mode app runs with {@code -Xmx1g} (see {@link #appJvmHeap}).
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
public class PingBenchmark {

    /** Which target to benchmark. {@code jvm} = java -jar fat jar; {@code native} = native exe. */
    @Param({"native"})
    private String mode;

    /** Heap cap for the JVM-mode target app (native binary heap is baked in at build time). */
    private static final String APP_JVM_HEAP = "-Xmx1g";

    private static final String HOST = "127.0.0.1";
    private static final int APP_PORT = 8080;
    private static final int ADMIN_PORT = 8081;
    private static final URI PING_URI = URI.create("http://" + HOST + ":" + APP_PORT + "/ping");
    private static final URI HEALTH_URI = URI.create("http://" + HOST + ":" + ADMIN_PORT + "/healthcheck");

    private HttpClient httpClient;
    private HttpRequest pingRequest;

    /** The target app process, started in @Setup, destroyed in @TearDown. */
    private final AtomicReference<Process> targetProcess = new AtomicReference<>();

    @Setup
    public void setup() throws Exception {
        // One HttpClient per benchmark JVM. HTTP/1.1 keep-alive (default) — matches real load.
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        pingRequest = HttpRequest.newBuilder(PING_URI)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        startTarget();
    }

    @TearDown
    public void tearDown() throws Exception {
        try {
            stopTarget();
        } finally {
            if (httpClient != null) {
                httpClient.close();
            }
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    @Warmup(iterations = 3, time = 5)
    @Measurement(iterations = 5, time = 5)
    @Fork(value = 1)
    public void ping(Blackhole bh) throws Exception {
        HttpResponse<String> resp = httpClient.send(pingRequest, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status != 200) {
            throw new IllegalStateException("ping returned " + status + ": " + resp.body());
        }
        bh.consume(resp.body());
    }

    // ---------------------------------------------------------------------
    //  External-process target harness
    // ---------------------------------------------------------------------

    private void startTarget() throws Exception {
        String projectRoot = resolveProjectRoot();
        String configPath = projectRoot + "/app/src/main/resources/application.yml";
        String javaExe = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");

        List<String> cmd = new ArrayList<>();
        switch (mode) {
            case "jvm" -> {
                String jarPath = projectRoot + "/app/target/dw5-graal-app.jar";
                cmd.add(javaExe);
                cmd.add(APP_JVM_HEAP);
                cmd.add("-jar");
                cmd.add(jarPath);
            }
            case "native" -> {
                String exePath = projectRoot + "/app/target/dw5-graal-app";
                cmd.add(exePath);
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode + " (expected jvm|native)");
        }
        cmd.add("server");
        cmd.add(configPath);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new java.io.File(projectRoot))
                .redirectErrorStream(true);
        // Inherit java.library.path for native lib deps if any (native exe is self-contained, jvm-mode jar may need libs).
        pb.environment().putIfAbsent("LD_LIBRARY_PATH",
                System.getProperty("java.home") + "/lib");

        Process p = pb.start();
        targetProcess.set(p);

        // Drain stdout on a background thread so the pipe buffer never fills and blocks the app.
        Thread drain = new Thread(() -> {
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {
                    // discard
                }
            } catch (IOException ignored) {
            }
        }, "target-stdout-drain");
        drain.setDaemon(true);
        drain.start();

        if (!waitForHealth(45)) {
            stopTarget();
            throw new IllegalStateException("target app (" + mode + ") did not become healthy within 45s");
        }
    }

    private boolean waitForHealth(int timeoutSeconds) {
        // Reuse a throwaway client with short timeouts for readiness probing.
        HttpClient probe = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest healthReq = HttpRequest.newBuilder(HEALTH_URI)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            // Bail early if the process died.
            Process p = targetProcess.get();
            if (p != null && !p.isAlive()) {
                return false;
            }
            try {
                HttpResponse<String> r = probe.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void stopTarget() {
        Process p = targetProcess.getAndSet(null);
        if (p == null) {
            return;
        }
        if (p.isAlive()) {
            // SIGINT first → Dropwizard shutdown hooks (clean Jetty stop). Then escalate.
            p.destroy(); // SIGTERM on Unix
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly(); // SIGKILL
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    /**
     * Resolve the project root. The benchmarks uberjar is run from the project root
     * (per BUILD-04: {@code java -jar benchmarks/target/benchmarks.jar}), so the CWD
     * is the project root. Fall back to walking up from the jar location.
     */
    private static String resolveProjectRoot() {
        String cwd = System.getProperty("user.dir");
        java.io.File marker = new java.io.File(cwd, "app/target/dw5-graal-app.jar");
        if (marker.exists()) {
            return cwd;
        }
        // Fall back: the code source of this class (inside benchmarks/target/benchmarks.jar).
        try {
            java.security.CodeSource cs = PingBenchmark.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.io.File jar = new java.io.File(cs.getLocation().toURI());
                // benchmarks/target/benchmarks.jar -> up three = project root
                java.io.File root = jar.getParentFile() != null ? jar.getParentFile().getParentFile() : null;
                if (root != null) {
                    root = root.getParentFile(); // project root
                    if (root != null && new java.io.File(root, "app").isDirectory()) {
                        return root.getAbsolutePath();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        // Last resort: assume CWD.
        return cwd;
    }
}
