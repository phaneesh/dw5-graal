# dw5-graal

A Dropwizard 5 (5.0.2) application compiled to a GraalVM native binary, with the
build configuration, runtime tuning, and profile-guided optimization (PGO) needed
to make it fast. The app is a minimal `/ping` service (Jersey + Jetty 12, virtual
threads, an mmap-backed persistent response queue) used as a native-image
performance benchmark.

```
app/         Dropwizard application (JVM fat jar + native-image builds)
benchmarks/  JMH harness + Python load-test scripts
```

## Prerequisites

- **GraalVM for JDK 25** on the PATH (JDK 25+ is required; the project compiles
  with `--release 25` and uses virtual threads). Verify with:

  ```bash
  java -version   # should report "GraalVM" / vendor Graal
  ```

- Maven 3.9+. All builds below are run from the repo root.
- `native-image` must be able to find a working C toolchain (`clang`, plus
  `libz` headers on most Linux distros). On macOS, Xcode command-line tools
  suffice.

## Build commands

```bash
# Plain JVM fat jar (target/dw5-graal-app.jar)
mvn -pl app package

# Native binary (target/dw5-graal-app)
mvn -Pnative -pl app package

# Run it
./app/target/dw5-graal-app server app/src/main/resources/application.yml
```

### PGO (profile-guided optimization) — two-step build

Native-image's `-O3` optimizes blindly; PGO feeds it a runtime profile so
inlining and branch layout match the real workload.

```bash
# 1. Build an instrumented binary
mvn -Pnative-pgo-instrument -pl app package

# 2. Run it and drive representative load through it (it writes default.iprof on exit)
./app/target/dw5-graal-app.pgo-instrumented server app/src/main/resources/application.yml
#   ... in another terminal:
python3 benchmarks/scripts/async_loadtest.py
#   then stop the server gracefully (SIGINT) — default.iprof lands in the CWD

# 3. Rebuild with the profile
mvn -Pnative-pgo -pl app package
# (profile path configurable with -Dpgo.file=/path/to/default.iprof)
```

Nuance: the profile is only collected when the instrumented process exits
cleanly — kill it too hard and you get no `.iprof`. Re-collect the profile
whenever the workload shape or thread configuration changes materially; a stale
profile can be worse than none.

## How the native build is configured

All native configuration lives in three Maven profiles in `app/pom.xml`
(`native`, `native-pgo-instrument`, `native-pgo`) using
`org.graalvm.buildtools:native-maven-plugin` **0.10.6**. Key decisions:

### Why 0.10.6 and not 1.x

native-maven-plugin 1.0.0 changed the reachability-metadata *format*
incompatibly. This project pins `0.10.6` deliberately; upgrading requires
regenerating the manually-carried metadata (see below).

### buildArgs (the actual knobs)

| Arg | Purpose |
| --- | --- |
| `-R:MaxHeapSize=${native.heap.size}` | Heap cap, baked into the binary at **build time** (default `1g`, parent pom). Unlike a JVM, you cannot change it with an env var at runtime — rebuild to change the heap. |
| `-O3` | Peak optimization (vs default `-O2`). Slower builds, faster binary. |
| `-march=native` | Emit code tuned to the **build machine's** CPU (AVX etc.). The binary may crash on older/different CPUs — drop this flag if you need a portable artifact. |
| `--gc=G1` | G1 GC instead of the default Serial GC. Serial's stop-the-world pauses destroy p99 and throughput under sustained load on a multi-core box. |
| `-H:Class=...PingApplication` | Entry point. |
| `-H:IncludeResources=application.yml` | Embed the YAML config into the binary — it is not on the classpath in a native image. |
| `-H:+ReportExceptionStackTraces` | Real stack traces instead of the shortened default (worth it for debugging; negligible runtime cost). |

One `<buildArg>` per XML element — never shell-concatenated into one string.

### Fat jar first, then native-image over it

The `native` profiles point native-image at `target/dw5-graal-app.jar`, which is
built by maven-shade-plugin with two transformers that matter:

- `ManifestResourceTransformer` — sets `Main-Class`.
- `ServicesResourceTransformer` — **merges** `META-INF/services` descriptors from
  all shaded jars (Jersey, HK2, logback). Without it, one jar's service file
  silently wins and Jersey SPI implementations go missing at runtime.

## Dropwizard + native-image: the hard parts

Dropwizard has **no first-party GraalVM support**, so three things had to be
solved manually:

### 1. Reachability metadata, carried by hand

`app/src/main/resources/META-INF/native-image/reachability-metadata.json`
(~374 reflection entries + 77 resource includes) tells native-image which
classes/methods Dropwizard touches only via reflection: the Jackson
serialization of the whole config tree (`server`, `logging`, `metrics`, …),
Jersey/HK2 components, logback, and the `ServiceLoader` resource globs
(`META-INF/services/*`). Upstream GraalVM reachability metadata doesn't cover
Dropwizard, so this file is maintained in-repo.

Practical workflow when it drifts:

1. Build with `-H:+ReportExceptionStackTraces` and run the binary.
2. A missing reflection entry surfaces as a `ClassNotFoundException` /
   `Method` not found at runtime naming the exact class/method.
3. Add the entry (with `allDeclaredConstructors`-style entries if you hit the
   constructor), rebuild.

### 2. Never register a resource *instance*

`environment.jersey().register(Object)` makes Dropwizard generate a **Javassist
proxy subclass with a random-UUID name at runtime** — fundamentally
incompatible with native-image's closed-world assumption (classes cannot be
generated at runtime). `PingApplication` therefore registers `PingResource.class`
plus an explicit HK2 `AbstractBinder` for constructor injection, and the
reachability metadata lists `PingResource`'s constructor and `ping()` method
directly.

### 3. SPI classes need META-INF/services entries

Custom `@JsonTypeName`-annotated subclasses are discovered via
`ServiceLoader`, so they appear in `META-INF/services/io.dropwizard.core.server.ServerFactory`
and `.../RequestLogFactory` (see next section) — and the corresponding service
globs must be in the reachability metadata's `resources` section.

## Runtime tuning (what actually made it fast)

### Carrier threads ≠ virtual threads (`TunedServerFactory`)

Dropwizard 5's `DefaultServerFactory` couples the platform carrier-thread cap
(`maxThreads`) to the virtual-thread concurrency limit — few carriers implies
few virtual threads. On the benchmark box (8 dedicated cores) we want ~8
carriers but a *high* VT cap. `TunedServerFactory` (`server.type: custom`)
overrides `createThreadPool` to decouple them:

- carriers: bounded by `maxThreads`/`minThreads` (platform threads, ~cores)
- virtual threads: separate `virtualThreadsMax` cap (default 10 000)

The admin pool is not decoupled (its VT cap still follows `adminMaxThreads`) —
irrelevant for real traffic.

### Other `application.yml` tuning

- `requestLog.type: noop` — `NoopRequestLogFactory.build()` returns `null` so no
  access log is attached at all. Per-request log formatting is a major CPU/I-O
  cost; and notably `ExternalRequestLogFactory` doesn't even *run* on native-image
  (reflective Jetty call not in the metadata).
- `gzip.enabled: false` — no compression work on the hot path.
- Connector tuning: 1 acceptor + 2 selectors, `acceptQueueSize: 2048`,
  `outputBufferSize: 32KiB`.
- `logging.level: WARN` — logback still runs, but stays quiet.
- The mmap-backed bigqueue (`PingQueue`, managed, closed on shutdown) adds a
  synchronous-but-cheap enqueue per request without Jackson on the hot path
  (`PingResource` hand-serializes the two-field JSON response).

### Optimization summary (and nuances)

| Optimization | Gain mechanism | Nuance / caveat |
| --- | --- | --- |
| `--gc=G1` | Parallel-ish GC; fewer long STW pauses than Serial | Higher memory + native heap; on 1 GB-capped, small heaps G1 needs headroom |
| `-O3` + `-march=native` | Best codegen for the build host | Binary not portable across CPU generations |
| PGO | Inlining + branch profile match real traffic | Profile must be collected on the same config and a clean exit; stale profiles regress |
| Heap baked at build time | Deterministic footprint | Change requires rebuild |
| Virtual threads + few carriers | High concurrency at low platform-thread cost | VT cap decoupled from carriers only by `TunedServerFactory` |
| Noop request log | Removes per-request formatting + I/O | No access logs — fine for benchmarks, reconsider in prod |
| Manual JSON serialization in `/ping` | Keeps Jackson off the hot path | Only viable for fixed-shape payloads |

## Benchmarks

- **JMH** (`benchmarks/`): external-process harness — `@Setup` starts the app as
  an OS process (JVM fat jar or native binary), polls the admin `healthcheck`
  until 200, then drives HTTP load. (JMH `@Fork` can't fork a native binary,
  hence the external-process design.)

  ```bash
  mvn -pl benchmarks package
  java -jar benchmarks/target/benchmarks.jar -p mode=native -bm thrpt PingBenchmark
  # or sample mode for p99:
  java -jar benchmarks/target/benchmarks.jar -p mode=native -bm sample PingBenchmark
  ```

  Heap parity note: the native binary has `-R:MaxHeapSize=1g` baked in; the
  JVM-mode target runs with `-Xmx1g` so the comparison is apples-to-apples.

- **Python saturation tests** (`benchmarks/scripts/`):
  - `async_loadtest.py` — N keep-alive connections, back-to-back requests,
    ops/s at concurrency 1→256.
  - `multi_client.py` — N independent client processes to check whether the
    single client was the bottleneck.
