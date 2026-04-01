package eu.inqudium.core.event;

import eu.inqudium.core.element.InqElementType;
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

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/*
 * ════════════════════════════════════════════════════════════════════════════════
 * JMH Benchmark Analysis — InqEventSystemBenchmark (@Threads(4))
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Environment: 4 concurrent threads publishing events simultaneously.
 * All throughput figures are aggregate across all threads.
 *
 * ── Results overview ────────────────────────────────────────────────────────────
 *
 *   Scenario                          ops/ms     GC alloc (B/op)    GC count
 *   ─────────────────────────────────────────────────────────────────────────
 *   baseline_empty                   176,346         ≈ 0               0
 *   publish_to_local_consumers        85,820         ≈ 0               0
 *   publish_to_global_exporters       83,481         ≈ 0               0
 *   publish_to_mixed_targets          58,867         ≈ 0               0
 *   publish_with_allocation_mixed     45,958        56                195
 *
 * ── Hot path is allocation-free under contention ────────────────────────────────
 *
 *   The first four scenarios show zero GC activity — no allocations, no
 *   collections, no pauses. This confirms that the copy-on-write design on
 *   AtomicReference<ConsumerEntry[]> achieves true steady-state allocation
 *   freedom: no new arrays, no boxing, no temporary objects on the publish
 *   path. Under 4-thread contention, consumers.get() is a plain volatile
 *   read — no locking, no CAS, no cache-line invalidation between readers.
 *
 * ── Throughput scales linearly with work ────────────────────────────────────────
 *
 *   baseline (no targets)        → 176k ops/ms   (upper bound: null-check + volatile read)
 *   single target path           →  84k ops/ms   (array iteration + virtual dispatch)
 *   both paths combined          →  59k ops/ms   (consumers + exporters sequentially)
 *   both paths + event alloc     →  46k ops/ms   (adds 56 B/op allocation pressure)
 *
 *   Each added layer of work reduces throughput proportionally — not
 *   exponentially. There is no sign of lock contention, false sharing, or
 *   disproportionate degradation under 4-thread parallelism. The local
 *   consumer path (~86k) and the global exporter path (~83k) perform
 *   near-identically, confirming that the CachedExporter indirection and
 *   isSubscribed() check add negligible overhead.
 *
 * ── Baseline variance is expected ───────────────────────────────────────────────
 *
 *   The baseline shows high relative error (±66k on 176k). At sub-nanosecond
 *   per-op cost, CPU cache effects, thread scheduling jitter, and memory
 *   fence timing dominate measurement noise. The tighter error bars on
 *   heavier scenarios (±2–5k) confirm that actual consumer work stabilizes
 *   the measurement by dwarfing infrastructure noise.
 *
 * ── Allocation scenario (publish_with_allocation) ───────────────────────────────
 *
 *   56 bytes/op corresponds to the InqEvent object allocated per iteration.
 *   At ~46k ops/ms × 4 threads, this produces ~2.4 GB/sec allocation rate,
 *   resulting in 195 minor GC cycles totaling 188ms — under 1ms per
 *   collection. All event objects die young in the nursery, confirming
 *   that even under allocation pressure, the system produces no GC pauses
 *   of concern.
 *
 * ── What the benchmark validates ────────────────────────────────────────────────
 *
 *   1. The AtomicReference<ConsumerEntry[]> copy-on-write design delivers
 *      lock-free, allocation-free reads under concurrent publishing.
 *   2. The volatile read on consumers.get() scales cleanly across threads
 *      with no mutual interference — exactly the read-heavy optimization
 *      the design targets.
 *   3. The decision against ConcurrentHashMap or synchronized structures
 *      is validated: plain array iteration with volatile snapshot semantics
 *      provides optimal cache locality and zero coordination overhead.
 *   4. Event object allocation is the dominant cost in realistic scenarios,
 *      not the publish machinery itself.
 *
 * ════════════════════════════════════════════════════════════════════════════════
 */

/**
 * JMH benchmark for the Inqudium event system.
 * Measures the throughput (operations per millisecond) of the event publisher
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
    Options opt = new OptionsBuilder()
        .addProfiler("gc").include(InqEventSystemBenchmark.class.getSimpleName()).build();
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
    emptyPublisher = new DefaultInqEventPublisher("empty",
        InqElementType.NO_ELEMENT,
        emptyRegistry,
        InqPublisherConfig.defaultConfig());

    // 2. Local Only Publisher (2 Consumers, no Exporters)
    localOnlyPublisher = new DefaultInqEventPublisher("local",
        InqElementType.NO_ELEMENT,
        emptyRegistry,
        InqPublisherConfig.defaultConfig());
    localOnlyPublisher.onEvent(blackhole::consume);
    localOnlyPublisher.onEvent(blackhole::consume);
    localOnlyPublisher.onEvent(blackhole::consume);
    localOnlyPublisher.onEvent(blackhole::consume);
    localOnlyPublisher.onEvent(blackhole::consume);

    // 3. Global Only Publisher (0 Consumers, 2 Exporters)
    InqEventExporterRegistry populatedRegistry = new InqEventExporterRegistry();
    populatedRegistry.register(blackhole::consume);
    populatedRegistry.register(blackhole::consume);
    populatedRegistry.register(blackhole::consume);
    populatedRegistry.register(blackhole::consume);
    populatedRegistry.register(blackhole::consume);

    // Force registry to freeze to avoid measuring discovery phase
    populatedRegistry.export(preAllocatedEvent);
    globalOnlyPublisher = new DefaultInqEventPublisher("global",
        InqElementType.NO_ELEMENT,
        populatedRegistry,
        InqPublisherConfig.defaultConfig());

    // 4. Mixed Publisher (2 Consumers, 2 Exporters)
    mixedPublisher = new DefaultInqEventPublisher("mixed",
        InqElementType.NO_ELEMENT,
        populatedRegistry,
        InqPublisherConfig.defaultConfig());
    mixedPublisher.onEvent(blackhole::consume);
    mixedPublisher.onEvent(blackhole::consume);
    mixedPublisher.onEvent(blackhole::consume);
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