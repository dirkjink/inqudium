package eu.inqudium.aspect.pipeline.integration;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

import java.util.concurrent.TimeUnit;

/**
 * Benchmark measuring the end-to-end overhead of AspectJ compile-time woven
 * pipeline advice under multi-thread load.
 *
 * <p>Structured to produce results directly comparable to
 * {@code ProxyMultiThreadedBenchmark} and {@code AspectPipelineMultiThreadedBenchmark}.
 * All three benchmarks use the same JMH configuration, the same
 * {@code Blackhole.consumeTokens(50)} workload, and the same profilers.</p>
 *
 * <p>Unlike the library benchmark (which measures pipeline logic in isolation
 * with plain lambdas), this benchmark calls <strong>real woven methods</strong>.
 * The measured time includes the full CTW dispatch chain:</p>
 * <pre>
 *   AspectJ ProceedingJoinPoint creation
 *     → aspectOf() singleton lookup
 *       → Method extraction from MethodSignature
 *         → ConcurrentHashMap.get (pipeline cache)
 *           → inline chain composition (reverse loop over action array)
 *             → chain execution → pjp.proceed() → original method body
 * </pre>
 *
 * <h3>Benchmark groups</h3>
 * <ul>
 *   <li><strong>Group 1 — Baseline vs. woven (single @Resilient):</strong>
 *       Non-woven baseline vs. 3-layer woven pipeline. The delta is the
 *       full CTW + pipeline overhead per invocation.</li>
 *   <li><strong>Group 2 — Per-layer annotation depth scaling:</strong>
 *       1 / 2 / 3 layers via per-layer annotations ({@code @Logged},
 *       {@code @Authorized @Logged}, {@code @Authorized @Logged @Timed}).
 *       Shows how pipeline depth affects latency with real woven dispatch.</li>
 * </ul>
 *
 * <h3>Key difference to the library benchmark</h3>
 * <table>
 *   <tr><th>Aspect</th><th>Library benchmark</th><th>This benchmark</th></tr>
 *   <tr><td>CTW</td><td>No — plain lambdas</td><td>Yes — real woven bytecode</td></tr>
 *   <tr><td>aspectOf()</td><td>Not involved</td><td>Called per invocation</td></tr>
 *   <tr><td>ProceedingJoinPoint</td><td>Not created</td><td>Created per invocation</td></tr>
 *   <tr><td>Method extraction</td><td>Pre-resolved in setup</td><td>Extracted from PJP</td></tr>
 *   <tr><td>Measures</td><td>Pipeline logic only</td><td>Full end-to-end</td></tr>
 * </table>
 *
 * <h3>Results (4 threads, 2 forks, consumeCPU(50), JDK 21, action-array pipeline)</h3>
 * <pre>
 * Benchmark                          ns/op     ±error   B/op   gc.count   Overhead vs baseline
 * ───────────────────────────────────────────────────────────────────────────────────────────────
 * baseline_nonWoven                   55.116    ±0.596      0          0   —
 * woven_perLayer_1Layer              139.342   ±13.541    184        377   +84 ns  (+153%)
 * woven_perLayer_2Layers             141.877   ±17.621    208        428   +87 ns  (+157%)
 * woven_perLayer_3Layers             167.777    ±7.758    232        377   +113 ns (+204%)
 * woven_resilient_3Layers            166.388   ±10.295    232        399   +111 ns (+202%)
 * </pre>
 *
 * <h3>Analysis</h3>
 *
 * <h4>1. Allocation profile — 24 bytes per chain lambda</h4>
 * <p>Unlike the previous ThreadLocal-based pipeline (which achieved 0 B/op),
 * the action-array pipeline creates N+1 lambdas per invocation (1 terminal +
 * N chain lambdas). These are <strong>not</strong> eliminated by escape
 * analysis because the loop-based composition prevents the JIT from proving
 * the lambdas do not escape.</p>
 *
 * <p>The allocation pattern is strictly linear:</p>
 * <pre>
 *   1 layer:  184 B/op
 *   2 layers: 208 B/op  (+24 B)
 *   3 layers: 232 B/op  (+24 B)
 *
 *   Per chain lambda: 24 bytes (16-byte object header + 2 compressed refs)
 *   Fixed overhead:   160 B/op (ProceedingJoinPoint + terminal lambda + AspectJ dispatch)
 *   Formula:          160 + N × 24 B/op
 * </pre>
 *
 * <h4>2. Fixed overhead is dominated by AspectJ, not the pipeline</h4>
 * <p>The 160 B/op fixed cost includes AspectJ's per-invocation
 * {@code ProceedingJoinPoint} allocation, {@code MethodSignature} access,
 * and the terminal lambda. The pipeline's own contribution (HashMap lookup,
 * AtomicLong increment, loop setup) is allocation-free. The pipeline adds
 * only the N × 24 B/op chain lambdas on top of AspectJ's fixed cost.</p>
 *
 * <h4>3. Latency overhead — ~84 ns fixed + ~14 ns per layer</h4>
 * <p>The fixed overhead of approximately 84 ns over baseline covers the full
 * AspectJ dispatch (PJP creation, aspectOf(), Method extraction) plus the
 * pipeline infrastructure (cache lookup, chain composition, terminal creation).
 * Each additional layer adds approximately 14 ns of chain traversal latency.
 * Error bars are elevated (±13–17 ns for 1–2 layers) due to GC pressure from
 * the per-call allocations.</p>
 *
 * <h4>4. GC impact is manageable</h4>
 * <p>With 377–428 GC collections across the measurement period and p50 pause
 * times of 0.6–0.8 ms, the allocation pressure is visible but not severe.
 * The short-lived chain lambdas (24 B each, no references to long-lived
 * objects) are collected efficiently in the young generation. G1 Eden usage
 * of ~950 MB indicates high throughput allocation, but Old Gen remains at
 * ~5 MB — no tenuring or promotion pressure.</p>
 *
 * <h4>5. Aspect implementation does not affect overhead</h4>
 * <p>{@code woven_resilient_3Layers} (166.4 ± 10.3 ns, 232 B/op) vs.
 * {@code woven_perLayer_3Layers} (167.8 ± 7.8 ns, 232 B/op) — identical
 * allocation, latency within error margin. Overhead scales with pipeline
 * depth, not with the specific aspect class.</p>
 *
 * <h4>6. Trade-off: ThreadLocal-free vs. allocation-free</h4>
 * <p>The previous ThreadLocal-based pipeline achieved 0 B/op and ~55 ns
 * (indistinguishable from baseline) but was incompatible with virtual
 * threads, reactive pipelines, and coroutines. The current action-array
 * approach trades allocation-freedom for universal thread-model
 * compatibility. The per-call cost of N × 24 bytes is predictable,
 * young-gen friendly, and negligible compared to any real-world layer
 * action (logging, metrics, I/O).</p>
 *
 * <h4>7. Conclusion</h4>
 * <p>The action-array pipeline adds approximately <strong>111 ns and 232
 * bytes</strong> per invocation for a 3-layer woven pipeline — of which
 * ~160 bytes and ~84 ns are AspectJ's fixed cost, not the pipeline's.
 * The pipeline's marginal contribution is <strong>24 bytes and ~14 ns
 * per layer</strong>. This is a conscious trade-off: the pipeline is safe
 * for virtual threads, reactive contexts, and coroutines, at the cost of
 * small, short-lived, young-gen allocations that do not cause tenuring
 * or promotion pressure.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(2)
@Threads(4)
public class WovenPipelineBenchmark {

    private BenchmarkService service;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(WovenPipelineBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    @Setup
    public void setup() {
        service = new BenchmarkService(Blackhole::consumeCPU);
    }

    // ======================== Group 1: Baseline vs. woven (@Resilient) ========================

    /**
     * Baseline: no annotation — method is not intercepted.
     * Measures consumeTokens(50) + method dispatch overhead only.
     *
     * <p>Analogous to {@code baseline_2Args} in the proxy benchmark.</p>
     *
     * <p>Result: 55.116 ± 0.596 ns/op, 0 B/op allocation.</p>
     */
    @Benchmark
    public void baseline_nonWoven(Blackhole bh) {
        bh.consume(service.baseline());
    }

    /**
     * @Resilient annotation — woven by ResilienceAspect, 3-layer pipeline
     * (AUTH → LOG → TIMING).
     *
     * <p>Analogous to {@code framework_2Args} in the proxy benchmark.
     * The delta to baseline is the full CTW + pipeline overhead.</p>
     *
     * <p>Result: 166.388 ± 10.295 ns/op, 232 B/op (160 fixed + 3 × 24).
     * Overhead vs baseline: +111 ns (+202%).</p>
     */
    @Benchmark
    public void woven_resilient_3Layers(Blackhole bh) {
        bh.consume(service.resilient3Layers());
    }

    // ======================== Group 2: Per-layer depth scaling ========================

    /**
     * 1 layer: @Logged only → [LOG].
     * Measures the minimal woven pipeline overhead (single layer).
     *
     * <p>Result: 139.342 ± 13.541 ns/op, 184 B/op (160 fixed + 1 × 24).
     * Overhead vs baseline: +84 ns (+153%). Elevated error bar due to
     * GC pressure from per-call allocations.</p>
     */
    @Benchmark
    public void woven_perLayer_1Layer(Blackhole bh) {
        bh.consume(service.perLayer1());
    }

    /**
     * 2 layers: @Authorized @Logged → [AUTH, LOG].
     *
     * <p>Result: 141.877 ± 17.621 ns/op, 208 B/op (160 fixed + 2 × 24).
     * Overhead vs baseline: +87 ns (+157%).</p>
     */
    @Benchmark
    public void woven_perLayer_2Layers(Blackhole bh) {
        bh.consume(service.perLayer2());
    }

    /**
     * 3 layers: @Authorized @Logged @Timed → [AUTH, LOG, TIME].
     *
     * <p>Directly comparable to {@code woven_resilient_3Layers} — both have
     * 3 layers but go through different aspects (PerLayerAspect vs.
     * ResilienceAspect). The delta of 1.4 ns and identical 232 B/op confirm
     * that overhead scales with pipeline depth, not the aspect class.</p>
     *
     * <p>Result: 167.777 ± 7.758 ns/op, 232 B/op (160 fixed + 3 × 24).
     * Overhead vs baseline: +113 ns (+204%).</p>
     */
    @Benchmark
    public void woven_perLayer_3Layers(Blackhole bh) {
        bh.consume(service.perLayer3());
    }
}
