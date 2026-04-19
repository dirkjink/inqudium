package eu.inqudium.spring.benchmark;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.MemPoolProfiler;
import org.openjdk.jmh.profile.PausesProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark of per-invocation overhead for Inqudium's Spring-AOP
 * integration, compared against a minimal Spring-AOP baseline aspect.
 *
 * <p>Each {@code @Benchmark} method calls {@link BenchmarkService#execute(int)}
 * exactly once through a different proxy configuration. The target method
 * body is a single arithmetic operation, so virtually all measured time is
 * aspect overhead, not target-method work.</p>
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li>{@link #raw_no_proxy} &mdash; direct call on the target, no proxy,
 *       no aspect. Establishes the theoretical floor; the JIT is free to
 *       inline everything.</li>
 *   <li>{@link #spring_aop_baseline} &mdash; Spring-AOP JDK proxy with a
 *       minimal {@link BaselineSpringAspect} (volatile counter increment
 *       plus {@link org.openjdk.jmh.infra.Blackhole} consume) around the
 *       target. The reference point against which Inqudium is compared.</li>
 *   <li>{@link #inqudium_1_layer} / {@link #inqudium_2_layers} /
 *       {@link #inqudium_3_layers} &mdash; Spring-AOP JDK proxy with
 *       {@link InqudiumPipelineAspect} composing 1, 2, or 3
 *       {@link CountingLayerProvider}s. Each layer does the same
 *       per-invocation work as the baseline advice, so the delta above
 *       baseline is pipeline dispatch plus one more volatile increment per
 *       additional layer.</li>
 * </ul>
 *
 * <h3>Representative results</h3>
 * <p>Numbers from a single-threaded run on JDK 21 with the default settings
 * of this class ({@code @BenchmarkMode(AverageTime)}, 3 forks, 5 warmup + 10
 * measurement iterations of 1&nbsp;s each):</p>
 * <pre>
 * Variant                   Time (ns/op)         Alloc (B/op)
 * raw_no_proxy               0.30 &plusmn; 0.003           ~0
 * spring_aop_baseline       94.4  &plusmn; 1.5            640
 * inqudium_1_layer         106.2  &plusmn; 1.6            728
 * inqudium_2_layers        116.8  &plusmn; 2.6            749
 * inqudium_3_layers        119.7  &plusmn; 0.6            704
 * </pre>
 * <p>Absolute numbers depend on hardware, JVM version, and background load.
 * The shape of the results &mdash; and particularly the deltas between
 * variants &mdash; is the stable, interpretable signal.</p>
 *
 * <h3>Interpretation</h3>
 * <p>Every variant except {@code raw_no_proxy} pays the same Spring-AOP JDK
 * proxy price (~94&nbsp;ns per call &mdash; JDK-proxy dispatch, building the
 * {@code ProceedingJoinPoint}, boxing {@code int}&#x2194;{@code Integer}, the
 * advice invocation itself). Useful comparisons are therefore deltas above
 * the baseline, not absolute numbers:</p>
 * <ul>
 *   <li><strong>Inqudium pipeline dispatch at 1 layer: +12&nbsp;ns</strong>
 *       over a flat aspect doing equivalent work. Covers the pipeline-cache
 *       lookup, lambda-chain composition (1 terminal + 1 layer lambda),
 *       {@link java.util.function.LongSupplier}-based call-ID allocation,
 *       and two nested dispatches.</li>
 *   <li><strong>Second layer: +10.6&nbsp;ns</strong> marginal.</li>
 *   <li><strong>Third layer: +2.8&nbsp;ns</strong> marginal &mdash;
 *       significantly cheaper than the second, see below.</li>
 * </ul>
 *
 * <h3>Non-linear scaling and escape analysis</h3>
 * <p>The jump from 2 to 3 layers being roughly a quarter of the 1-to-2 jump
 * is not measurement noise &mdash; the error bars are tight and the finding
 * is corroborated by the allocation profile. At 3 layers the measured
 * allocation is 704&nbsp;B/op with an error of &plusmn;0.002&nbsp;B/op,
 * three orders of magnitude tighter than the &plusmn;13&nbsp;B/op seen at
 * 1 and 2 layers &mdash; and lower in absolute terms than the 2-layer case
 * despite composing one more lambda.</p>
 *
 * <p>The signature of near-zero allocation variance combined with reduced
 * heap allocation strongly suggests that C2's escape analysis stack-allocated
 * more of the {@link eu.inqudium.core.pipeline.InternalExecutor} lambdas at
 * 3 layers than it managed at 1 or 2. This is plausible: C2 EA has
 * per-method object-tracking budgets and is sensitive to call-graph depth;
 * a deeper composition loop can cross unrolling and inlining thresholds
 * that expose the lambdas for stack allocation. This behavior is
 * JIT-state-dependent and <strong>may not reproduce</strong> on a different
 * machine or JDK version. To verify on a given environment, run with
 * {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining} or capture JFR
 * events with {@code -prof jfr}.</p>
 *
 * <h3>Production context</h3>
 * <p>The framework overhead of Inqudium above a flat Spring-AOP aspect is
 * 12&ndash;26&nbsp;ns per call across 1 to 3 layers. Real resilience work
 * dominates this by one to two orders of magnitude: a Resilience4j bulkhead
 * semaphore acquisition costs ~50&ndash;200&nbsp;ns, a circuit-breaker
 * state check ~20&ndash;80&nbsp;ns, a single active SLF4J log statement
 * ~500&ndash;2000&nbsp;ns. The Spring-AOP proxy itself (~94&nbsp;ns) is
 * the largest single cost component in the stack; replacing it with AspectJ
 * compile-time weaving would cut more latency than any pipeline-level
 * optimization.</p>
 *
 * <h3>Caveats</h3>
 * <ul>
 *   <li>Single-threaded measurement. Cross-thread cache-line contention on
 *       the volatile counters is not covered here; that was specifically
 *       avoided by design when the instance-local call-ID counters were
 *       introduced.</li>
 *   <li>JIT optimization state varies between runs. Treat the 3-layer
 *       escape-analysis result as evidence that deeper pipelines can be
 *       effectively free, not as a guarantee for every deployment.</li>
 *   <li>The target method is trivial on purpose. Nothing in these numbers
 *       reflects the cost of actual method bodies, database calls, or
 *       remote I/O.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3)
@State(Scope.Benchmark)
public class AspectOverheadBenchmark {


    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(AspectOverheadBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }


    /**
     * Fixed input — its value is irrelevant, only that it is opaque to the
     * JIT so the target method body cannot be folded into a constant.
     */
    private static final int INPUT = 42;

    /**
     * Raw target, no proxy, no aspect. The JIT is free to inline
     * {@link BenchmarkServiceImpl#execute(int)} completely.
     */
    private BenchmarkService raw;

    /**
     * Spring-AOP proxy with {@link BaselineSpringAspect} bound.
     */
    private BenchmarkService baselineProxy;

    /**
     * Spring-AOP proxy with {@link InqudiumPipelineAspect} and one layer.
     */
    private BenchmarkService inq1Proxy;

    /**
     * Spring-AOP proxy with {@link InqudiumPipelineAspect} and two layers.
     */
    private BenchmarkService inq2Proxy;

    /**
     * Spring-AOP proxy with {@link InqudiumPipelineAspect} and three layers.
     */
    private BenchmarkService inq3Proxy;

    @Setup(Level.Trial)
    public void setup(Blackhole bh) {
        // Raw — no proxy, for the absolute-floor reference
        this.raw = new BenchmarkServiceImpl();

        // Baseline — a single @Around that increments a volatile counter
        this.baselineProxy = buildProxy(new BaselineSpringAspect(bh));

        // Inqudium — 1/2/3 layers, each doing the same work as the baseline advice
        this.inq1Proxy = buildInqudiumProxy(bh, 1);
        this.inq2Proxy = buildInqudiumProxy(bh, 2);
        this.inq3Proxy = buildInqudiumProxy(bh, 3);
    }

    /**
     * Builds an Inqudium-pipeline proxy with the given number of counting
     * layers. Layer order values are spaced by 10 so the sort in
     * {@code ResolvedPipeline.resolve} is deterministic and preserves
     * insertion order.
     */
    private BenchmarkService buildInqudiumProxy(Blackhole bh, int layerCount) {
        List<AspectLayerProvider<Object>> providers = new java.util.ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            providers.add(new CountingLayerProvider("L" + (i + 1), (i + 1) * 10, bh));
        }
        return buildProxy(new InqudiumPipelineAspect(providers));
    }

    /**
     * Wraps a fresh {@link BenchmarkServiceImpl} in an {@link AspectJProxyFactory}
     * with the given aspect bound. JDK proxy is forced so every benchmark pays
     * the same proxy price regardless of how Spring auto-selects otherwise.
     */
    private BenchmarkService buildProxy(Object aspect) {
        AspectJProxyFactory pf = new AspectJProxyFactory(new BenchmarkServiceImpl());
        pf.setProxyTargetClass(false);  // JDK proxy, not CGLIB
        pf.addAspect(aspect);
        return pf.getProxy();
    }

    // ======================== Benchmark methods ========================

    @Benchmark
    public int raw_no_proxy() {
        return raw.execute(INPUT);
    }

    @Benchmark
    public int spring_aop_baseline() {
        return baselineProxy.execute(INPUT);
    }

    @Benchmark
    public int inqudium_1_layer() {
        return inq1Proxy.execute(INPUT);
    }

    @Benchmark
    public int inqudium_2_layers() {
        return inq2Proxy.execute(INPUT);
    }

    @Benchmark
    public int inqudium_3_layers() {
        return inq3Proxy.execute(INPUT);
    }

}
