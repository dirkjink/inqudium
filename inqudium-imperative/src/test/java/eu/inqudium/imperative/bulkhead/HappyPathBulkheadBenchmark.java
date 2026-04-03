package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.config.InqConfig;
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

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HappyPathBulkheadBenchmark {

  // We set the Bulkhead limit to 10 for all tests
  private static final int BULKHEAD_LIMIT = 10;
  private Semaphore semaphore;
  private Bulkhead bulkhead;

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .addProfiler("gc").include(HappyPathBulkheadBenchmark.class.getSimpleName()).build();
    new Runner(opt).run();
  }

  @Setup(Level.Trial)
  public void setUp() {
    semaphore = new Semaphore(BULKHEAD_LIMIT);
    var config = InqConfig.configure()
        .general()
        .with(bulkhead(), c -> c
                .name("test")
                .maxConcurrentCalls(10)
            //  .maxWaitDuration(Duration.ofMillis(5))
        ).build();
    bulkhead = Bulkhead.of(config);
  }

  /**
   * MEASURING PURE OVERHEAD:
   * Threads (10) <= Permits (10).
   * Every thread always gets a permit immediately. No rejections, no waiting.
   */
  @Benchmark
  @Threads(10)
  public void measurePureOverheadBulkhead(Blackhole blackhole) throws InterruptedException {
    bulkhead.executeRunnable(() -> simulateWork(blackhole));
  }

  /**
   * MEASURING CONTENTION (QUEUEING):
   * Threads (20) > Permits (10).
   * The bulkhead is saturated. Threads must wait, but NONE are rejected.
   */
  @Benchmark
  @Threads(20)
  public void measureContentionWithoutRejectionsBulkhead(Blackhole blackhole) throws InterruptedException {
    bulkhead.executeRunnable(() -> simulateWork(blackhole));
  }

  /**
   * MEASURING PURE OVERHEAD:
   * Threads (10) <= Permits (10).
   * Every thread always gets a permit immediately. No rejections, no waiting.
   */
  @Benchmark
  @Threads(10)
  public void measurePureOverheadSemaphore(Blackhole blackhole) throws InterruptedException {
    // We use acquire. Since Threads <= Permits, it will never block.
    semaphore.acquire();
    try {
      simulateWork(blackhole);
    } finally {
      semaphore.release();
    }
  }

  /**
   * MEASURING CONTENTION (QUEUEING):
   * Threads (20) > Permits (10).
   * The bulkhead is saturated. Threads must wait, but NONE are rejected.
   */
  @Benchmark
  @Threads(20)
  public void measureContentionWithoutRejectionsSemaphore(Blackhole blackhole) throws InterruptedException {
    // We use acquire() instead of tryAcquire().
    // Threads will queue up and wait. We measure the throughput of the
    // actual work PLUS the waiting/queueing overhead of the bulkhead.
    semaphore.acquire();
    try {
      simulateWork(blackhole);
    } finally {
      semaphore.release();
    }
  }

  /**
   * BASELINE:
   * For comparison, the pure work without any bulkhead.
   */
  @Benchmark
  @Threads(10)
  public void baselineNoBulkhead(Blackhole blackhole) {
    simulateWork(blackhole);
  }

  private void simulateWork(Blackhole blackhole) {
    blackhole.consume(1);
  }
}
