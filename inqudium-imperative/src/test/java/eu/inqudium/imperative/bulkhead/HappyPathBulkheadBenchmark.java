package eu.inqudium.imperative.bulkhead;

import dev.failsafe.Bulkhead;
import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeExecutor;
import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.event.BulkheadEventConfig;
import io.github.resilience4j.bulkhead.BulkheadConfig;
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
 * <h2>Benchmark categories</h2>
 * <ul>
 *   <li><b>PureOverhead (10 threads, 10 permits):</b> No contention — measures the
 *       facade overhead (acquire + release + telemetry) in isolation.</li>
 *   <li><b>ContentionWithoutRejections (20 threads, 10 permits):</b> Sustained contention —
 *       measures queuing efficiency under load.</li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 * <pre>
 * io.github.resilience4j:resilience4j-bulkhead:2.2.0
 * dev.failsafe:failsafe:3.3.2
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
  private static final int WAIT_MILLIS = 5;

  // ── Raw Semaphore (baseline) ──
  private Semaphore semaphore;

  // ── Inqudium ──
  private eu.inqudium.imperative.bulkhead.Bulkhead inqBulkhead;

  // ── Resilience4j ──
  private io.github.resilience4j.bulkhead.Bulkhead r4jBulkhead;

  // ── Failsafe ──
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

    // ── Inqudium: rejectionsOnly to minimize event overhead ──
    var config = InqConfig.configure()
        .general()
        .with(bulkhead(), c -> c
            .name("test")
            .maxConcurrentCalls(BULKHEAD_LIMIT)
            .eventConfig(BulkheadEventConfig.rejectionsOnly())
            .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
        ).build();
    inqBulkhead = eu.inqudium.imperative.bulkhead.Bulkhead.of(config);

    // ── Resilience4j: fair call handling (default), writable stack trace disabled ──
    BulkheadConfig r4jConfig = BulkheadConfig.custom()
        .maxConcurrentCalls(BULKHEAD_LIMIT)
        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
        .fairCallHandlingStrategyEnabled(true)
        .writableStackTraceEnabled(false)
        .build();
    r4jBulkhead = io.github.resilience4j.bulkhead.Bulkhead.of("r4j-test", r4jConfig);

    // ── Failsafe: fair queuing (default), 5 ms max wait ──
    Bulkhead<Void> failsafeBulkhead = Bulkhead.<Void>builder(BULKHEAD_LIMIT)
        .withMaxWaitTime(Duration.ofMillis(WAIT_MILLIS))
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
    inqBulkhead.executeRunnable(() -> simulateWork(blackhole));
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
    inqBulkhead.executeRunnable(() -> simulateWork(blackhole));
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
