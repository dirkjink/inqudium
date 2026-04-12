package eu.inqudium.imperative.bulkhead;

import dev.failsafe.Failsafe;
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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;

/**
 * Comparative bulkhead benchmark using <b>async wrappers</b> with pre-completed
 * {@link CompletableFuture} — no thread scheduling involved.
 *
 * <p>This is the async wrapper counterpart to {@link HappyPathBulkheadBenchmarkTwo}.
 * The underlying function creates a {@link CompletableFuture#completedFuture completed}
 * future synchronously — no thread is started. This isolates the async pipeline overhead
 * (two-phase acquire/release, stage chaining via {@code whenComplete}) from any scheduling
 * or thread-pool cost.</p>
 *
 * <h2>Batch pattern (Escape Analysis prevention)</h2>
 * <p>Each benchmark iteration creates {@value #BATCH_SIZE} stages in a first loop, then
 * consumes them in a second loop. This prevents the JIT from scalar-replacing the
 * {@link CompletableFuture} objects: they must survive across the loop boundary and into
 * the array, forcing heap allocation — which is what happens in production where futures
 * outlive their creation scope.</p>
 * <pre>{@code
 * CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
 * for (int i = 0; i < BATCH_SIZE; i++) {
 *     stages[i] = decorated.get();       // create
 * }
 * for (CompletionStage<Void> stage : stages) {
 *     bh.consume(stage.toCompletableFuture().get());  // consume
 * }
 * }</pre>
 * <p>{@code @OperationsPerInvocation(BATCH_SIZE)} normalizes throughput to ops/ms per
 * single operation.</p>
 *
 * <h2>Async setup per library</h2>
 * <ul>
 *   <li><b>Inqudium:</b> {@code decorateAsyncRunnable(Runnable)} — the async wrapper chain
 *       attaches permit release via {@code .whenComplete()} on the returned stage.</li>
 *   <li><b>Resilience4j:</b> {@code Bulkhead.decorateCompletionStage(bulkhead, supplier)} —
 *       R4j acquires the permit, invokes the supplier, and attaches
 *       {@code .whenComplete((result, throwable) -> bulkhead.onComplete())} for async
 *       permit release.</li>
 *   <li><b>Failsafe:</b> {@code Failsafe.with(bulkhead).getStageAsync(supplier)} — Failsafe's
 *       built-in async execution with permit management.</li>
 *   <li><b>Raw Semaphore:</b> Manual acquire/release with synchronous
 *       {@code CompletableFuture.completedFuture(null)}.</li>
 * </ul>
 *
 * <h2>Permit accounting with completed futures</h2>
 * <p>Since the delegate returns an already-completed future, {@code whenComplete()} fires
 * immediately — the permit is acquired and released within a single {@code decorated.get()}
 * call. Each thread therefore holds at most <b>one</b> permit at a time, regardless of
 * {@link #BATCH_SIZE}. The batch affects only CompletableFuture object lifetime (for
 * Escape Analysis prevention), not concurrent permit usage.</p>
 *
 * <p>The {@link #BULKHEAD_LIMIT} is set to {@code PURE_OVERHEAD_THREADS × BATCH_SIZE}
 * ({@value #PURE_OVERHEAD_THREADS} × {@value #BATCH_SIZE} = 80) to guarantee zero
 * contention in the overhead benchmarks, even if all threads are in the creation loop
 * simultaneously. The contention benchmarks use {@value #CONTENTION_THREADS} threads
 * (2:1 ratio to permits) to exceed the limit.</p>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>Batch size: {@value #BATCH_SIZE} stages per iteration</li>
 *   <li>Max concurrent calls: {@code PURE_OVERHEAD_THREADS × BATCH_SIZE} = 80</li>
 *   <li>Max wait time: {@value #WAIT_MILLIS} ms</li>
 *   <li>Pure overhead threads: {@value #PURE_OVERHEAD_THREADS} (× {@value #BATCH_SIZE} = 80 ≤ 80 permits)</li>
 *   <li>Contention threads: 2 × permits = 160 (2:1 ratio)</li>
 *   <li>Work: {@code Blackhole.consumeCPU(100)} per call</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class HappyPathBulkheadBenchmarkFour {

    private static final int BATCH_SIZE = 3;
    private static final int PURE_OVERHEAD_THREADS = 5;
    private static final int BULKHEAD_LIMIT = PURE_OVERHEAD_THREADS * BATCH_SIZE;  // 15
    private static final int CONTENTION_THREADS = BULKHEAD_LIMIT * 2;              // 30
    private static final int WAIT_MILLIS = 150;
    // ── Failsafe metrics counters ──
    private final LongAdder failsafeSuccess = new LongAdder();
    private final LongAdder failsafeFailure = new LongAdder();

    // ── Pre-decorated async wrappers (created once in setUp, invoked per iteration) ──
    private Supplier<CompletionStage<Void>> decoratedSemaphore;
    private Supplier<CompletionStage<Void>> decoratedInqDiagnostic;
    private Supplier<CompletionStage<Void>> decoratedInqOptimized;
    private Supplier<CompletionStage<Void>> decoratedR4j;
    private Supplier<CompletionStage<Void>> decoratedFailsafe;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(HappyPathBulkheadBenchmarkFour.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    private static void simulateWork() {
        Blackhole.consumeCPU(100);
    }

    // ════════════════════════════════════════════════════════════════════
    // SETUP — all async wrappers are created here, not per invocation
    // ════════════════════════════════════════════════════════════════════

    @Setup(Level.Trial)
    public void setUp() {

        // ── Raw Semaphore (baseline — manual async wrapper) ──
        Semaphore semaphore = new Semaphore(BULKHEAD_LIMIT, true);
        decoratedSemaphore = () -> {
            try {
                if (semaphore.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    try {
                        simulateWork();
                    } finally {
                        semaphore.release();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        };

        // ── Inqudium diagnostic: all events ──
        var diagnosticConfig = InqConfig.configure()
                .general()
                .with(bulkhead(), c -> c
                        .name("async-diagnostic")
                        .maxConcurrentCalls(BULKHEAD_LIMIT)
                        .eventConfig(BulkheadEventConfig.diagnostic())
                        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                ).build();
        var inqDiagnostic = Bulkhead.of(diagnosticConfig);
        decoratedInqDiagnostic = inqDiagnostic.decorateAsyncRunnable(
                HappyPathBulkheadBenchmarkFour::simulateWork);

        // ── Inqudium optimized: rejections only ──
        var optimizedConfig = InqConfig.configure()
                .general()
                .with(bulkhead(), c -> c
                        .name("async-optimized")
                        .maxConcurrentCalls(BULKHEAD_LIMIT)
                        .eventConfig(BulkheadEventConfig.standard())
                        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                ).build();
        var inqOptimized = Bulkhead.of(optimizedConfig);
        decoratedInqOptimized = inqOptimized.decorateAsyncRunnable(
                HappyPathBulkheadBenchmarkFour::simulateWork);

        // ── Resilience4j: BulkheadRegistry + Micrometer (production setup) ──
        BulkheadConfig r4jConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(BULKHEAD_LIMIT)
                .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                .fairCallHandlingStrategyEnabled(true)
                .writableStackTraceEnabled(false)
                .build();
        BulkheadRegistry r4jRegistry = BulkheadRegistry.of(r4jConfig);
        var r4jBulkhead = r4jRegistry.bulkhead("r4j-async-test");

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaggedBulkheadMetrics.ofBulkheadRegistry(r4jRegistry).bindTo(meterRegistry);

        decoratedR4j = io.github.resilience4j.bulkhead.Bulkhead.decorateCompletionStage(
                r4jBulkhead, () -> {
                    simulateWork();
                    return CompletableFuture.completedFuture(null);
                });

        // ── Failsafe: event listeners ──
        dev.failsafe.Bulkhead<Void> failsafeBulkhead = dev.failsafe.Bulkhead.<Void>builder(BULKHEAD_LIMIT)
                .withMaxWaitTime(Duration.ofMillis(WAIT_MILLIS))
                .onSuccess(event -> failsafeSuccess.increment())
                .onFailure(event -> failsafeFailure.increment())
                .build();
        var failsafeExecutor = Failsafe.with(failsafeBulkhead);
        decoratedFailsafe = () -> failsafeExecutor.getStageAsync(() -> {
            simulateWork();
            return CompletableFuture.completedFuture(null);
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // BASELINE — no bulkhead, only CompletableFuture + batch overhead
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void baselineNoBulkhead(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            simulateWork();
            stages[i] = CompletableFuture.completedFuture(null);
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PURE OVERHEAD — Threads (10) << Permits (25), no contention
    // Batch of 8 stages: create all, then consume all.
    // @OperationsPerInvocation normalizes throughput to per-operation.
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measurePureOverheadSemaphore(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedSemaphore.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measurePureOverheadInqudium(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedInqDiagnostic.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measurePureOverheadInqudiumOptimized(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedInqOptimized.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measurePureOverheadResilience4j(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedR4j.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(PURE_OVERHEAD_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measurePureOverheadFailsafe(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedFailsafe.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CONTENTION — Threads (50) > Permits (25), no rejections
    // Same batch pattern, more threads than permits.
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(CONTENTION_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measureContentionSemaphore(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedSemaphore.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(CONTENTION_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measureContentionInqudium(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedInqDiagnostic.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(CONTENTION_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measureContentionInqudiumOptimized(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedInqOptimized.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(CONTENTION_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measureContentionResilience4j(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedR4j.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }

    @Benchmark
    @Threads(CONTENTION_THREADS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void measureContentionFailsafe(Blackhole bh) throws Exception {
        CompletionStage<Void>[] stages = new CompletionStage[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            stages[i] = decoratedFailsafe.get();
        }
        for (CompletionStage<Void> stage : stages) {
            bh.consume(stage.toCompletableFuture().get());
        }
    }
}
