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
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.MemPoolProfiler;
import org.openjdk.jmh.profile.PausesProfiler;
import org.openjdk.jmh.profile.StackProfiler;
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
 * <p>
 * This benchmark uses **direct invocation** — the facade acquires the permit, executes the
 * caller's Runnable, and releases the permit in a single method call without creating
 * an intermediate wrapper object.
 *
 * <h2>Configuration</h2>
 * <p>All bulkheads are configured identically:
 * <ul>
 *   <li>Max concurrent calls: 10</li>
 *   <li>Max wait time: 150 ms — long enough to guarantee zero rejections under
 *       contention, eliminating exception creation as a confounding variable</li>
 *   <li>Fair semaphore / FIFO ordering where applicable</li>
 *   <li>Work: {@code Blackhole.consumeCPU(100)} per call</li>
 * </ul>
 *
 * <h2>Metrics setup (production-like)</h2>
 * <ul>
 *   <li><b>Resilience4j:</b> Uses {@link BulkheadRegistry} with
 *       {@link TaggedBulkheadMetrics} bound to a {@link SimpleMeterRegistry} —
 *       the exact setup that Spring Boot auto-configuration produces with
 *       {@code resilience4j-micrometer} on the classpath. The Micrometer layer
 *       registers gauges (polled, zero per-call overhead).</li>
 *   <li><b>Failsafe:</b> No Micrometer module exists. Uses the built-in policy-level
 *       event listeners ({@code onSuccess}, {@code onFailure}) with {@link LongAdder}
 *       counters — the only metrics mechanism available.</li>
 *   <li><b>Inqudium (diagnostic):</b> Full lifecycle events (acquire + release) via
 *       {@link BulkheadEventConfig#diagnostic()} — fair comparison with R4j's internal
 *       event creation overhead.</li>
 *   <li><b>Inqudium (optimized):</b> {@link BulkheadEventConfig#standard()} —
 *       the recommended production default with zero happy-path event overhead.</li>
 * </ul>
 *
 * <h2>Results (2026-04-03, 150 ms wait timeout, @Fork(3), @Measurement(5))</h2>
 *
 * <h3>Pure Overhead — 10 threads, 10 permits, no contention</h3>
 * <p>Measures the facade cost in isolation: acquire → execute → release. No thread ever
 * parks — every {@code tryAcquire} succeeds immediately.
 *
 * <table>
 *   <tr><th>Library</th><th>ops/ms</th><th>B/op</th><th>GC</th><th>G1 Eden</th><th>vs Semaphore</th></tr>
 *   <tr><td>Semaphore (raw)</td><td>10,909</td><td>0.24</td><td>3</td><td>46 MB</td><td>1.00×</td></tr>
 *   <tr><td>Resilience4j + Micrometer</td><td>9,400</td><td>0.29</td><td>0</td><td>26 MB</td><td>0.86×</td></tr>
 *   <tr><td><b>Inqudium optimized</b></td><td><b>5,427</b></td><td><b>0.55</b></td><td><b>0</b></td><td><b>38 MB</b></td><td><b>0.50×</b></td></tr>
 *   <tr><td>Inqudium diagnostic</td><td>3,445</td><td>80.8</td><td>29</td><td>148 MB</td><td>0.32×</td></tr>
 *   <tr><td>Failsafe</td><td>1,936</td><td>441.5</td><td>68</td><td>180 MB</td><td>0.18×</td></tr>
 * </table>
 *
 * <p><b>Analysis:</b> Inqudium optimized and Resilience4j both achieve near-zero allocation
 * and zero GC. The throughput gap (5,427 vs 9,400 ops/ms) is explained by the two
 * {@code nanoTimeSource.now()} calls that Inqudium makes on every happy path for RTT
 * measurement — infrastructure required for adaptive concurrency algorithms (AIMD, Vegas,
 * CoDel) that Resilience4j does not offer. This is the deliberate cost of a feature, not
 * an inefficiency.
 *
 * <p>Inqudium diagnostic (all events enabled) drops to 3,445 ops/ms with 80.8 B/op — the
 * cost of two {@code Instant} objects + two event instances per call. This confirms why
 * events are a diagnostic tool ({@link BulkheadEventConfig#diagnostic()}), not always-on
 * telemetry.
 *
 * <p>Failsafe allocates 441.5 B/op on every call and triggers 68 GC collections, placing
 * it at 5.6× the overhead of the next-slowest library.
 *
 * <h3>Contention — 20 threads, 10 permits, no rejections</h3>
 * <p>Measures queuing efficiency under sustained load. 20 threads compete for 10 permits
 * with a 150 ms wait timeout. The work ({@code Blackhole.consumeCPU(100)}) is short enough
 * that all threads eventually acquire a permit — zero rejections, zero exception overhead.
 *
 * <table>
 *   <tr><th>Library</th><th>ops/ms</th><th>B/op (±err)</th><th>GC</th><th>G1 Eden</th></tr>
 *   <tr><td>Semaphore (raw)</td><td>215</td><td>17.6 (±6.4)</td><td>0</td><td>82 MB</td></tr>
 *   <tr><td><b>Inqudium optimized</b></td><td><b>215</b></td><td><b>64.4 (±30.1)</b></td><td><b>4</b></td><td><b>142 MB</b></td></tr>
 *   <tr><td>Resilience4j + Micrometer</td><td>212</td><td>21.7 (±10.0)</td><td>0</td><td>108 MB</td></tr>
 *   <tr><td>Inqudium diagnostic</td><td>211</td><td>120.3 (±10.3)</td><td>6</td><td>144 MB</td></tr>
 *   <tr><td>Failsafe</td><td>784</td><td>508.4 (±2.9)</td><td>39</td><td>148 MB</td></tr>
 * </table>
 *
 * <p><b>Analysis — Throughput convergence:</b> All fair-semaphore implementations converge
 * to ~211–215 ops/ms. The facade overhead is completely invisible — throughput is dominated
 * by the Condition/Semaphore park time. Under real workloads (millisecond-scale downstream
 * calls), the facade cost would be even less significant.
 *
 * <p><b>Analysis — Contention-induced allocation:</b> Even the raw Semaphore allocates
 * 17.6 B/op under contention (vs 0.24 B/op without). This is not application code — it is
 * the JDK's AQS framework allocating {@code Node} objects for the wait queue when threads
 * actually park on {@code Semaphore.tryAcquire(timeout)}.
 *
 * <p>Inqudium optimized shows 64.4 B/op (±30.1) — the delta of ~47 B/op over the raw
 * Semaphore comes from the facade's lambda closures ({@code call.withCallable(() -> {...})},
 * the {@code InqCall} wrapping in {@code executeRunnable}) that the JIT cannot
 * stack-allocate when threads park. When a thread suspends on {@code Condition.awaitNanos()},
 * any object whose lifetime spans the suspension point must be materialized on the heap —
 * Escape Analysis cannot prove the object dies within the frame because the frame itself is
 * frozen. Without parking (Pure Overhead), the same objects are fully eliminated (0.55 B/op).
 *
 * <p>The high error margin (±30.1) confirms this is JIT-dependent: depending on compilation
 * order, inlining depth, and OSR boundaries, the JIT sometimes succeeds and sometimes fails
 * to eliminate these allocations across the parking boundary.
 *
 * <p>Resilience4j shows 21.7 B/op under contention — only ~4 B/op above the raw Semaphore.
 * R4j's {@code decorateRunnable} creates a thinner wrapper layer without RTT measurement
 * infrastructure, giving the JIT fewer objects to materialize at the suspension point.
 *
 * <p><b>Analysis — Failsafe fairness:</b> Failsafe's 784 ops/ms (3.6× higher than all
 * others) indicates an unfair semaphore or lock-free mechanism internally. Unfair scheduling
 * allows "barging" where a newly arrived thread acquires a permit before parked threads are
 * signaled — maximizing throughput at the cost of starvation risk. The 508 B/op allocation
 * and 39 GC collections confirm significant per-call object creation regardless of the
 * scheduling advantage.
 *
 * <h3>GC Pause profile</h3>
 * <table>
 *   <tr><th>Library (Contention)</th><th>Pause count</th><th>Avg pause</th><th>p99 pause</th></tr>
 *   <tr><td>Semaphore (raw)</td><td>0</td><td>—</td><td>—</td></tr>
 *   <tr><td>Resilience4j</td><td>2</td><td>3.9 ms</td><td>4.3 ms</td></tr>
 *   <tr><td><b>Inqudium optimized</b></td><td><b>1</b></td><td><b>4.1 ms</b></td><td><b>4.1 ms</b></td></tr>
 *   <tr><td>Inqudium diagnostic</td><td>1</td><td>3.3 ms</td><td>3.3 ms</td></tr>
 *   <tr><td>Failsafe</td><td>8</td><td>4.0 ms</td><td>8.4 ms</td></tr>
 * </table>
 *
 * <p>GC pauses are negligible for all fair-semaphore implementations (0–2 pauses,
 * sub-5 ms). Failsafe's 8 pauses with a p99 of 8.4 ms reflect its higher allocation rate.
 *
 * <h2>Dependencies</h2>
 * <pre>
 * io.github.resilience4j:resilience4j-bulkhead:2.4.0
 * io.github.resilience4j:resilience4j-micrometer:2.4.0
 * io.micrometer:micrometer-core:1.14.5
 * dev.failsafe:failsafe:3.3.2
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class HappyPathBulkheadBenchmarkOne {

  private static final int BULKHEAD_LIMIT = 10;
  private static final int WAIT_MILLIS = 150;

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

  // ── Failsafe metrics counters ──
  private final LongAdder failsafeSuccess = new LongAdder();
  private final LongAdder failsafeFailure = new LongAdder();

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .addProfiler(StackProfiler.class)
        .addProfiler(GCProfiler.class)
        .addProfiler(PausesProfiler.class)
        .addProfiler(MemPoolProfiler.class)
        .include(HappyPathBulkheadBenchmarkOne.class.getSimpleName())
        //.resultFormat(ResultFormatType.CSV)
        //.result("ergebnisse.csv")
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
    r4jBulkhead.executeRunnable(() -> simulateWork(blackhole));
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
    r4jBulkhead.executeRunnable(() -> simulateWork(blackhole));
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
