package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the Inqudium event system.
 * * Measures the throughput (operations per millisecond) of the event publisher
 * and registry under concurrent load.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4) // Simulates 4 concurrent threads pushing events simultaneously
@State(Scope.Benchmark) // State is shared across all benchmark threads
public class InqEventSystemBenchmark {

  private InqEvent preAllocatedEvent;

  // ─── Benchmark State Setup ───────────────────────────────────────────────
  // Different publishers for different scenarios
  private InqEventPublisher emptyPublisher;
  private InqEventPublisher localOnlyPublisher;
  private InqEventPublisher globalOnlyPublisher;
  private InqEventPublisher mixedPublisher;

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder().addProfiler("gc").include(InqEventSystemBenchmark.class.getSimpleName()).build();
    new Runner(opt).run();
  }

  @Setup(Level.Trial)
  public void setup(Blackhole blackhole) {
    // Pre-allocate the event to measure routing overhead, not GC/allocation overhead
    preAllocatedEvent = new InqEvent("bench-call", "bench-element", InqElementType.NO_ELEMENT, Instant.now()) {
    };

    // 1. Empty Publisher (Baseline overhead)
    InqEventExporterRegistry emptyRegistry = new InqEventExporterRegistry();
    // Force registry to freeze into an empty state
    emptyRegistry.export(preAllocatedEvent);
    emptyPublisher = new DefaultInqEventPublisher("empty", InqElementType.NO_ELEMENT, emptyRegistry);

    // 2. Local Only Publisher (2 Consumers, no Exporters)
    localOnlyPublisher = new DefaultInqEventPublisher("local", InqElementType.NO_ELEMENT, emptyRegistry);
    localOnlyPublisher.onEvent(blackhole::consume);
    localOnlyPublisher.onEvent(blackhole::consume);

    // 3. Global Only Publisher (0 Consumers, 2 Exporters)
    InqEventExporterRegistry populatedRegistry = new InqEventExporterRegistry();
    populatedRegistry.register(blackhole::consume);
    populatedRegistry.register(blackhole::consume);
    // Force registry to freeze to avoid measuring discovery phase
    populatedRegistry.export(preAllocatedEvent);
    globalOnlyPublisher = new DefaultInqEventPublisher("global", InqElementType.NO_ELEMENT, populatedRegistry);

    // 4. Mixed Publisher (2 Consumers, 2 Exporters)
    mixedPublisher = new DefaultInqEventPublisher("mixed", InqElementType.NO_ELEMENT, populatedRegistry);
    mixedPublisher.onEvent(blackhole::consume);
    mixedPublisher.onEvent(blackhole::consume);
  }

  // ─── Benchmarks ──────────────────────────────────────────────────────────

  /**
   * Measures the raw baseline overhead of the publish() method call and iteration,
   * without actually invoking any listeners.
   */
  @Benchmark
  public void publish_baseline_empty() {
    emptyPublisher.publish(preAllocatedEvent);
  }

  /**
   * Measures the overhead of routing events to local instances (ConcurrentHashMap iteration).
   */
  @Benchmark
  public void publish_to_local_consumers() {
    localOnlyPublisher.publish(preAllocatedEvent);
  }

  /**
   * Measures the overhead of routing events out to the global registry (List iteration).
   */
  @Benchmark
  public void publish_to_global_exporters() {
    globalOnlyPublisher.publish(preAllocatedEvent);
  }

  /**
   * Measures a realistic scenario where an event flows through both local and global routes.
   */
  @Benchmark
  public void publish_to_mixed_targets() {
    mixedPublisher.publish(preAllocatedEvent);
  }

  /**
   * Measures the overhead of allocating a NEW event every time plus routing it.
   * This is closer to real-world impact as events are usually instantiated right before publishing.
   */
  @Benchmark
  public void publish_with_allocation_to_mixed_targets() {
    InqEvent freshEvent = new InqEvent("bench-call", "bench-element", InqElementType.NO_ELEMENT, Instant.now()) {
    };
    mixedPublisher.publish(freshEvent);
  }
}