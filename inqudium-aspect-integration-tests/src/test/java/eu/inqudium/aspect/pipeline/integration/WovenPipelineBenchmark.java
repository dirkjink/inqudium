package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.integration.perlayer.BenchmarkService;
import org.openjdk.jmh.annotations.*;
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
 *           → pre-composed chain execution
 *             → pjp.proceed() → original method body
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
        service = new BenchmarkService();
    }

    // ======================== Group 1: Baseline vs. woven (@Resilient) ========================

    /**
     * Baseline: no annotation — method is not intercepted.
     * Measures consumeTokens(50) + method dispatch overhead only.
     *
     * <p>Analogous to {@code baseline_2Args} in the proxy benchmark.</p>
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
     */
    @Benchmark
    public void woven_resilient_3Layers(Blackhole bh) {
        bh.consume(service.resilient3Layers());
    }

    // ======================== Group 2: Per-layer depth scaling ========================

    /**
     * 1 layer: @Logged only → [LOG].
     * Measures the minimal woven pipeline overhead (single layer).
     */
    @Benchmark
    public void woven_perLayer_1Layer(Blackhole bh) {
        bh.consume(service.perLayer1());
    }

    /**
     * 2 layers: @Authorized @Logged → [AUTH, LOG].
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
     * ResilienceAspect). Results should be nearly identical, confirming
     * that the overhead comes from the pipeline depth, not the aspect
     * implementation.</p>
     */
    @Benchmark
    public void woven_perLayer_3Layers(Blackhole bh) {
        bh.consume(service.perLayer3());
    }
}
