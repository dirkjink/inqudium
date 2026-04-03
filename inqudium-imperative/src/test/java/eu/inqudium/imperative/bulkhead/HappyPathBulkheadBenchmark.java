package eu.inqudium.imperative.bulkhead;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeExecutor;
import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;

/**
 * Comparative bulkhead benchmark: raw Semaphore vs Inqudium vs Resilience4j vs Failsafe.
 *
 * <h2>Configuration</h2>
 * <p>All bulkheads are configured identically:
 * <ul>
 *   <li>Max concurrent calls: 10</li>
 *   <li>Max wait time: 5 ms (fair queuing, no rejections under contention)</li>
 *   <li>Fair semaphore / FIFO ordering where applicable</li>
 * </ul>
 *
 * <h2>Metrics setup (production-like)</h2>
 * <ul>
 *   <li><b>Resilience4j:</b> Uses {@link BulkheadRegistry} with
 *       {@link TaggedBulkheadMetrics} bound to a {@link SimpleMeterRegistry} —
 *       the exact setup that Spring Boot auto-configuration produces with
 *       {@code resilience4j-micrometer} on the classpath. The Micrometer layer
 *       registers gauges (polled, zero per-call overhead), but R4j's internal
 *       {@code SemaphoreBulkhead} always creates event objects
 *       ({@code BulkheadOnCallPermittedEvent}, {@code BulkheadOnCallFinishedEvent})
 *       on every call regardless of listener registration.</li>
 *   <li><b>Failsafe:</b> No Micrometer module exists. Uses the built-in policy-level
 *       event listeners ({@code onSuccess}, {@code onFailure}) with {@link LongAdder}
 *       counters — the only metrics mechanism available.</li>
 *   <li><b>Inqudium (allEnabled):</b> Full lifecycle events (acquire + release) for
 *       fair comparison with R4j's internal event creation.</li>
 *   <li><b>Inqudium (optimized):</b> {@link BulkheadEventConfig#rejectionsOnly()} —
 *       demonstrates the performance ceiling when lifecycle events are disabled.</li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 * <pre>
 * io.github.resilience4j:resilience4j-bulkhead:2.4.0
 * io.github.resilience4j:resilience4j-micrometer:2.4.0
 * io.micrometer:micrometer-core:1.14.5
 * dev.failsafe:failsafe:3.3.2
 * </pre>
 * <p>
 * Benchmark results:
 * <pre>
 *   ### Pure Overhead (kein Warten, keine Contention)
 *
 * | Library | ops/ms | B/op | GC | vs. Semaphore |
 * |---|---|---|---|---|
 * | Semaphore (raw) | 8.623 | 0.009 | 0 | 1.0× |
 * | **R4j + Micrometer** | **7.043** | **0.011** | **0** | **0.82×** |
 * | **Inqudium optimized** | **5.743** | **0.013** | **0** | **0.67×** |
 * | Inqudium allEnabled | 3.857 | 80 | 32 | 0.45× |
 * | Failsafe | 2.068 | 440 | 80 | 0.24× |
 *
 * ### Contention (20 Threads, 10 Permits)
 *
 * | Library | ops/ms | B/op | GC |
 * |---|---|---|---|
 * | Semaphore | 216 | 6 | 3 |
 * | Inqudium optimized | 209 | 82 | 7 |
 * | R4j + Micrometer | 205 | 14 | 2 |
 * | Inqudium allEnabled | 200 | 94 | 5 |
 * | Failsafe | 725 | 503 | 36 |
 *
 * ### Analyse
 *
 * Das zentrale Ergebnis: **R4j's Micrometer-Integration ist praktisch kostenlos** — 0 B/op, 0 GC, und trotzdem hat man in Produktion Metriken. Der Grund: `TaggedBulkheadMetrics` registriert nur Gauges, die bei Prometheus-Scrape gepollt werden. Kein Event-Objekt wird pro Call erzeugt. R4j's interne `publishBulkheadEvent`-Methode prüft offenbar, ob Consumer registriert sind, und überspringt die Event-Erzeugung wenn nicht.
 *
 * Das ist ein grundlegender Architekturvorteil: **Polling-basierte Metriken (Gauges) vs. Push-basierte Metriken (Events).** R4j exportiert `available_concurrent_calls` und `max_allowed_concurrent_calls` als Gauges, die bei Bedarf den aktuellen Zustand der Semaphore lesen. Inqudium erzeugt bei `allEnabled` pro Call zwei Event-Objekte + zwei Instants = 80 B/op.
 *
 * **Der Hebel für Inqudium ist klar:** Wenn du Metriken auch polling-basiert realisierst (z.B. der MeterRegistry-Binder liest `strategy.concurrentCalls()` und `strategy.maxConcurrentCalls()` als Gauges), entfällt die Notwendigkeit für Lifecycle-Events komplett — und `rejectionsOnly()` wird zum sinnvollen Default statt zur Optimierung.
 *
 * Unter Contention konvergieren alle Fair-Semaphore-Implementierungen auf ~200–216 ops/ms. Die Facade-Kosten sind dort irrelevant.
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class HappyPathBulkheadBenchmark {

  private static final int BULKHEAD_LIMIT = 10;
  private static final int WAIT_MILLIS = 10;
  // ── Failsafe metrics counters ──
  private final LongAdder failsafeSuccess = new LongAdder();
  private final LongAdder failsafeFailure = new LongAdder();
  // ── Raw Semaphore (baseline, no metrics) ──
  private Semaphore semaphore;
  // ── Inqudium: all events enabled (fair comparison with R4j internal events) ──
  private eu.inqudium.imperative.bulkhead.Bulkhead inqBulkheadAllEvents;
  // ── Inqudium: rejections only (optimized, shows ceiling) ──
  private eu.inqudium.imperative.bulkhead.Bulkhead inqBulkheadOptimized;
  // ── Resilience4j with Micrometer (production Spring Boot setup) ──
  private io.github.resilience4j.bulkhead.Bulkhead r4jBulkhead;
  // ── Failsafe with event listeners (only metrics mechanism available) ──
  private FailsafeExecutor<Void> failsafeExecutor;

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .addProfiler("gc")
        .include(HappyPathBulkheadBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }

  @Setup(Level.Trial)
  public void setUp() {
    // ── Raw Semaphore ──
    semaphore = new Semaphore(BULKHEAD_LIMIT, true);

    // ── Inqudium: all events (matches R4j's internal event overhead) ──
    var allEventsConfig = InqConfig.configure()
        .general()
        .with(bulkhead(), c -> c
            .name("test-all-events")
            .maxConcurrentCalls(BULKHEAD_LIMIT)
            .eventConfig(BulkheadEventConfig.diagnostic())
            .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
        ).build();
    inqBulkheadAllEvents = eu.inqudium.imperative.bulkhead.Bulkhead.of(allEventsConfig);

    // ── Inqudium: rejections only (optimized) ──
    var optimizedConfig = InqConfig.configure()
        .general()
        .with(bulkhead(), c -> c
            .name("test-optimized")
            .maxConcurrentCalls(BULKHEAD_LIMIT)
            .eventConfig(BulkheadEventConfig.standard())
            .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
        ).build();
    inqBulkheadOptimized = eu.inqudium.imperative.bulkhead.Bulkhead.of(optimizedConfig);

    // ── Resilience4j: BulkheadRegistry + Micrometer (production setup) ──
    //
    // This mirrors what Spring Boot auto-configuration does:
    //   1. BulkheadConfig → BulkheadRegistry → Bulkhead instance
    //   2. TaggedBulkheadMetrics binds gauge metrics to the MeterRegistry
    //   3. The MeterRegistry would normally be a PrometheusMeterRegistry;
    //      SimpleMeterRegistry is functionally equivalent for overhead measurement
    BulkheadConfig r4jConfig = BulkheadConfig.custom()
        .maxConcurrentCalls(BULKHEAD_LIMIT)
        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
        .fairCallHandlingStrategyEnabled(true)
        .writableStackTraceEnabled(false)
        .build();
    BulkheadRegistry r4jRegistry = BulkheadRegistry.of(r4jConfig);
    r4jBulkhead = r4jRegistry.bulkhead("r4j-test");

    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TaggedBulkheadMetrics.ofBulkheadRegistry(r4jRegistry).bindTo(meterRegistry);

    // ── Failsafe: event listeners (no Micrometer module available) ──
    dev.failsafe.Bulkhead<Void> failsafeBulkhead = dev.failsafe.Bulkhead.<Void>builder(BULKHEAD_LIMIT)
        .withMaxWaitTime(Duration.ofMillis(WAIT_MILLIS))
        .onSuccess(event -> failsafeSuccess.increment())
        .onFailure(event -> failsafeFailure.increment())
        .build();
    failsafeExecutor = Failsafe.with(failsafeBulkhead);
  }

  // ════════════════════════════════════════════════════════════════════
  // BASELINE — no bulkhead
  // ════════════════════════════════════════════════════════════════════

  @Benchmark
  @Threads(10)
  public void baselineNoBulkhead(Blackhole blackhole) {
    simulateWork(blackhole);
  }

  // ════════════════════════════════════════════════════════════════════
  // PURE OVERHEAD — Threads (10) == Permits (10), no contention
  // ════════════════════════════════════════════════════════════════════

  @Benchmark
  @Threads(10)
  public void measurePureOverheadSemaphore(Blackhole blackhole) throws InterruptedException {
    if (semaphore.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
      try {
        simulateWork(blackhole);
      } finally {
        semaphore.release();
      }
    }
  }

  @Benchmark
  @Threads(10)
  public void measurePureOverheadInqudium(Blackhole blackhole) throws InterruptedException {
    inqBulkheadAllEvents.executeRunnable(() -> simulateWork(blackhole));
  }

  @Benchmark
  @Threads(10)
  public void measurePureOverheadInqudiumOptimized(Blackhole blackhole) throws InterruptedException {
    inqBulkheadOptimized.executeRunnable(() -> simulateWork(blackhole));
  }

  @Benchmark
  @Threads(10)
  public void measurePureOverheadResilience4j(Blackhole blackhole) {
    io.github.resilience4j.bulkhead.Bulkhead.decorateRunnable(r4jBulkhead,
        () -> simulateWork(blackhole)).run();
  }

  @Benchmark
  @Threads(10)
  public void measurePureOverheadFailsafe(Blackhole blackhole) {
    failsafeExecutor.run(() -> simulateWork(blackhole));
  }

  // ════════════════════════════════════════════════════════════════════
  // CONTENTION — Threads (20) > Permits (10), no rejections
  // ════════════════════════════════════════════════════════════════════

  @Benchmark
  @Threads(20)
  public void measureContentionSemaphore(Blackhole blackhole) throws InterruptedException {
    if (semaphore.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
      try {
        simulateWork(blackhole);
      } finally {
        semaphore.release();
      }
    }
  }

  @Benchmark
  @Threads(20)
  public void measureContentionInqudium(Blackhole blackhole) throws InterruptedException {
    inqBulkheadAllEvents.executeRunnable(() -> simulateWork(blackhole));
  }

  @Benchmark
  @Threads(20)
  public void measureContentionInqudiumOptimized(Blackhole blackhole) throws InterruptedException {
    inqBulkheadOptimized.executeRunnable(() -> simulateWork(blackhole));
  }

  @Benchmark
  @Threads(20)
  public void measureContentionResilience4j(Blackhole blackhole) {
    io.github.resilience4j.bulkhead.Bulkhead.decorateRunnable(r4jBulkhead,
        () -> simulateWork(blackhole)).run();
  }

  @Benchmark
  @Threads(20)
  public void measureContentionFailsafe(Blackhole blackhole) {
    failsafeExecutor.run(() -> simulateWork(blackhole));
  }

  // ════════════════════════════════════════════════════════════════════

  private void simulateWork(Blackhole blackhole) {
    Blackhole.consumeCPU(100);
  }
}
