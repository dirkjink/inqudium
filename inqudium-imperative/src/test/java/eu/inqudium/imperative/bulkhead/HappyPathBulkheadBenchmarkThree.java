package eu.inqudium.imperative.bulkhead;

import dev.failsafe.Failsafe;
import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.pipeline.proxy.InqProxyFactory;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;

/**
 * Comparative bulkhead benchmark using <b>dynamic proxies</b> instead of decorated Runnables.
 *
 * <p>This is the proxy-based counterpart to {@link HappyPathBulkheadBenchmarkTwo}.
 * All proxies are created once in {@link #setUp()} — the benchmark methods measure only
 * the proxy method invocation (handler dispatch → acquire → work → release), not proxy
 * creation.</p>
 *
 * <h2>Difference to BenchmarkTwo</h2>
 * <p>BenchmarkTwo uses {@code decorateRunnable()} which creates a thin wrapper Runnable.
 * BenchmarkThree uses dynamic proxies via {@link InqProxyFactory} (for Inqudium) and
 * hand-crafted {@code Proxy#newProxyInstance} (for raw Semaphore, Resilience4j, and
 * Failsafe) to measure the overhead of proxy-based dispatch. This is the pattern used
 * when protecting entire service interfaces rather than individual operations.</p>
 *
 * <h2>Configuration</h2>
 * <p>Identical to BenchmarkTwo:
 * <ul>
 *   <li>Max concurrent calls: 10</li>
 *   <li>Max wait time: 150 ms — guarantees zero rejections</li>
 *   <li>Fair semaphore / FIFO ordering where applicable</li>
 *   <li>Work: {@code Blackhole.consumeCPU(100)} per call</li>
 * </ul>
 *
 * <h2>Proxy setup</h2>
 * <ul>
 *   <li><b>Raw Semaphore:</b> {@code Proxy.newProxyInstance} with inline acquire/release</li>
 *   <li><b>Inqudium (diagnostic / optimized):</b> {@link BulkheadProxyFactory} —
 *       uses {@link InqProxyFactory} internally with the bulkhead as {@code LayerAction}</li>
 *   <li><b>Resilience4j:</b> {@code Proxy.newProxyInstance} wrapping R4j's
 *       {@code Bulkhead.acquirePermission()} / {@code releasePermission()}</li>
 *   <li><b>Failsafe:</b> {@code Proxy.newProxyInstance} wrapping Failsafe's executor</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class HappyPathBulkheadBenchmarkThree {

    private static final int BULKHEAD_LIMIT = 10;
    private static final int WAIT_MILLIS = 150;

    // ── Failsafe metrics counters ──
    private final LongAdder failsafeSuccess = new LongAdder();
    private final LongAdder failsafeFailure = new LongAdder();

    // ── Pre-built proxies (created once in setUp, invoked per iteration) ──
    private WorkService proxySemaphore;
    private WorkService proxyInqDiagnostic;
    private WorkService proxyInqOptimized;
    private WorkService proxyR4j;
    private WorkService proxyFailsafe;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(HappyPathBulkheadBenchmarkThree.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    private static void simulateWork() {
        Blackhole.consumeCPU(100);
    }

    /**
     * Creates a dynamic proxy for the WorkService interface with the given handler.
     * Used for raw Semaphore, Resilience4j, and Failsafe where InqProxyFactory is not applicable.
     */
    @SuppressWarnings("unchecked")
    private static WorkService createManualProxy(WorkService target, InvocationHandler handler) {
        return (WorkService) Proxy.newProxyInstance(
                WorkService.class.getClassLoader(),
                new Class<?>[]{WorkService.class},
                handler);
    }

    // ════════════════════════════════════════════════════════════════════
    // SETUP — all proxies are created here, not per invocation
    // ════════════════════════════════════════════════════════════════════

    @Setup(Level.Trial)
    public void setUp() {

        WorkService realService = HappyPathBulkheadBenchmarkThree::simulateWork;

        // ── Raw Semaphore proxy ──
        Semaphore semaphore = new Semaphore(BULKHEAD_LIMIT, true);
        proxySemaphore = createManualProxy(realService, (proxy, method, args) -> {
            try {
                if (semaphore.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    try {
                        return method.invoke(realService, args);
                    } finally {
                        semaphore.release();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        // ── Inqudium diagnostic: all events (matches R4j's internal event overhead) ──
        var diagnosticConfig = InqConfig.configure()
                .general()
                .with(bulkhead(), c -> c
                        .name("proxy-diagnostic")
                        .maxConcurrentCalls(BULKHEAD_LIMIT)
                        .eventConfig(BulkheadEventConfig.diagnostic())
                        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                ).build();
        var inqDiagnostic = Bulkhead.of(diagnosticConfig);
        proxyInqDiagnostic = new BulkheadProxyFactory(inqDiagnostic)
                .protect(WorkService.class, realService);

        // ── Inqudium optimized: rejections only ──
        var optimizedConfig = InqConfig.configure()
                .general()
                .with(bulkhead(), c -> c
                        .name("proxy-optimized")
                        .maxConcurrentCalls(BULKHEAD_LIMIT)
                        .eventConfig(BulkheadEventConfig.standard())
                        .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                ).build();
        var inqOptimized = Bulkhead.of(optimizedConfig);
        proxyInqOptimized = new BulkheadProxyFactory(inqOptimized)
                .protect(WorkService.class, realService);

        // ── Resilience4j: BulkheadRegistry + Micrometer (production setup) ──
        BulkheadConfig r4jConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(BULKHEAD_LIMIT)
                .maxWaitDuration(Duration.ofMillis(WAIT_MILLIS))
                .fairCallHandlingStrategyEnabled(true)
                .writableStackTraceEnabled(false)
                .build();
        BulkheadRegistry r4jRegistry = BulkheadRegistry.of(r4jConfig);
        var r4jBulkhead = r4jRegistry.bulkhead("r4j-proxy-test");

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaggedBulkheadMetrics.ofBulkheadRegistry(r4jRegistry).bindTo(meterRegistry);

        proxyR4j = createManualProxy(realService, (proxy, method, args) -> {
            r4jBulkhead.acquirePermission();
            try {
                return method.invoke(realService, args);
            } finally {
                r4jBulkhead.releasePermission();
            }
        });

        // ── Failsafe: event listeners (no Micrometer module available) ──
        dev.failsafe.Bulkhead<Object> failsafeBulkhead = dev.failsafe.Bulkhead.builder(BULKHEAD_LIMIT)
                .withMaxWaitTime(Duration.ofMillis(WAIT_MILLIS))
                .onSuccess(event -> failsafeSuccess.increment())
                .onFailure(event -> failsafeFailure.increment())
                .build();
        var failsafeExecutor = Failsafe.with(failsafeBulkhead);

        proxyFailsafe = createManualProxy(realService, (proxy, method, args) ->
                failsafeExecutor.get(() -> method.invoke(realService, args)));
    }

    @Benchmark
    @Threads(10)
    public void baselineNoBulkhead() {
        simulateWork();
    }

    // ════════════════════════════════════════════════════════════════════
    // BASELINE — no bulkhead, no proxy
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(10)
    public void measurePureOverheadSemaphore() {
        proxySemaphore.doWork();
    }

    // ════════════════════════════════════════════════════════════════════
    // PURE OVERHEAD — Threads (10) == Permits (10), no contention
    // All proxies are pre-created in setUp — the benchmark measures only
    // the proxy invocation (handler → acquire → work → release).
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(10)
    public void measurePureOverheadInqudium() {
        proxyInqDiagnostic.doWork();
    }

    @Benchmark
    @Threads(10)
    public void measurePureOverheadInqudiumOptimized() {
        proxyInqOptimized.doWork();
    }

    @Benchmark
    @Threads(10)
    public void measurePureOverheadResilience4j() {
        proxyR4j.doWork();
    }

    @Benchmark
    @Threads(10)
    public void measurePureOverheadFailsafe() {
        proxyFailsafe.doWork();
    }

    @Benchmark
    @Threads(20)
    public void measureContentionSemaphore() {
        proxySemaphore.doWork();
    }

    // ════════════════════════════════════════════════════════════════════
    // CONTENTION — Threads (20) > Permits (10), no rejections
    // Same pre-created proxies, more threads than permits.
    // ════════════════════════════════════════════════════════════════════

    @Benchmark
    @Threads(20)
    public void measureContentionInqudium() {
        proxyInqDiagnostic.doWork();
    }

    @Benchmark
    @Threads(20)
    public void measureContentionInqudiumOptimized() {
        proxyInqOptimized.doWork();
    }

    @Benchmark
    @Threads(20)
    public void measureContentionResilience4j() {
        proxyR4j.doWork();
    }

    @Benchmark
    @Threads(20)
    public void measureContentionFailsafe() {
        proxyFailsafe.doWork();
    }

    /**
     * Minimal service interface for proxy-based benchmarking.
     */
    public interface WorkService {
        void doWork();
    }
}
