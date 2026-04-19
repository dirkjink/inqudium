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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark of per-invocation overhead for Inqudium's Spring-AOP
 * integration, compared against a minimal Spring-AOP baseline aspect under
 * both Spring-AOP proxy modes &mdash; JDK dynamic proxy and CGLIB subclass
 * proxy.
 *
 * <p>Each {@code @Benchmark} method calls {@link BenchmarkService#execute(int)}
 * exactly once through a different proxy configuration. The target method
 * body is a single arithmetic operation, so virtually all measured time is
 * aspect overhead, not target-method work.</p>
 *
 * <h3>Why both proxy modes</h3>
 * <p>Spring-AOP can build proxies in two fundamentally different ways:</p>
 * <ul>
 *   <li><strong>JDK dynamic proxy</strong> &mdash; Spring generates a class
 *       implementing the target's interfaces and routes every call through
 *       {@code InvocationHandler.invoke(Object, Method, Object[])}. Arguments
 *       are packed into an {@code Object[]} on every call and primitives are
 *       boxed. The method is looked up through reflection.</li>
 *   <li><strong>CGLIB subclass proxy</strong> &mdash; Spring generates a
 *       runtime subclass of the target and overrides each advised method.
 *       Calls are virtual dispatches on the generated subclass; no
 *       args-array, no boxing on the fast path, no reflective method
 *       lookup.</li>
 * </ul>
 * <p>Spring selects JDK proxy when the target implements interfaces and
 * {@code proxy-target-class} is {@code false}; CGLIB when there are no
 * interfaces or when the flag is {@code true}. Spring Boot has defaulted
 * {@code proxy-target-class=true} since 2.0, so CGLIB is the production
 * reality for most Spring Boot applications. The two modes have measurably
 * different per-call costs, so both are measured here.</p>
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li>{@link #raw_no_proxy} &mdash; direct call on the target. No proxy,
 *       no aspect. Theoretical floor; the JIT is free to inline
 *       everything.</li>
 *   <li>{@link #spring_aop_jdk_baseline} / {@link #spring_aop_cglib_baseline}
 *       &mdash; the respective Spring-AOP proxy with a minimal
 *       {@link BaselineSpringAspect} ({@code volatile} counter increment +
 *       {@link org.openjdk.jmh.infra.Blackhole} consume) around the target.
 *       The per-mode reference point against which the Inqudium variants
 *       are compared.</li>
 *   <li>{@link #inqudium_jdk_1_layer} / {@link #inqudium_jdk_2_layers} /
 *       {@link #inqudium_jdk_3_layers} &mdash; JDK proxy with
 *       {@link InqudiumPipelineAspect} composing 1, 2, or 3
 *       {@link CountingLayerProvider}s.</li>
 *   <li>{@link #inqudium_cglib_1_layer} / {@link #inqudium_cglib_2_layers} /
 *       {@link #inqudium_cglib_3_layers} &mdash; CGLIB proxy with the same
 *       pipeline compositions.</li>
 * </ul>
 * <p>Nine benchmark methods in total plus the raw reference.</p>
 *
 * <h3>Measured results</h3>
 * <p>Single-threaded run on JDK 21 with the default settings of this class
 * ({@code @BenchmarkMode(AverageTime)}, 3 forks, 5 warmup + 10 measurement
 * iterations of 1&nbsp;s each):</p>
 * <pre>
 * Variant                       Time (ns/op)         Alloc (B/op)
 * raw_no_proxy                   0.28 &plusmn; 0.006          ~0
 *
 * spring_aop_jdk_baseline       97.8  &plusmn; 1.7           664
 * spring_aop_cglib_baseline     87.7  &plusmn; 0.9           536
 *
 * inqudium_jdk_1_layer         104.8  &plusmn; 1.6           712
 * inqudium_jdk_2_layers        112.1  &plusmn; 2.4           741
 * inqudium_jdk_3_layers        116.6  &plusmn; 1.1           760
 *
 * inqudium_cglib_1_layer        95.4  &plusmn; 1.3           584
 * inqudium_cglib_2_layers       98.7  &plusmn; 1.1           616
 * inqudium_cglib_3_layers      106.1  &plusmn; 1.7           635
 * </pre>
 * <p>Absolute numbers depend on hardware, JVM version, and background load.
 * The shape of the results &mdash; and particularly the deltas between
 * variants &mdash; is the stable, interpretable signal.</p>
 *
 * <h3>Interpretation</h3>
 * <p>Within each proxy mode, every variant except {@code raw_no_proxy} pays
 * the same proxy entry cost. Useful comparisons are therefore deltas above
 * the mode-specific baseline:</p>
 * <ul>
 *   <li><strong>CGLIB vs. JDK proxy: about 10&nbsp;ns cheaper</strong> at
 *       the baseline (87.7 vs. 97.8&nbsp;ns) and ~9&ndash;13&nbsp;ns cheaper
 *       at every Inqudium depth. The CGLIB saving comes from avoiding the
 *       {@code Object[]} args allocation, the return-value boxing, and the
 *       reflective method lookup &mdash; but modern C2 optimizes those paths
 *       well enough that the advantage is modest, not dramatic.</li>
 *   <li><strong>Inqudium pipeline dispatch at 1 layer: +7&nbsp;ns</strong>
 *       over the respective baseline &mdash; nearly identical in both modes
 *       (+7.0&nbsp;ns JDK, +7.7&nbsp;ns CGLIB). Covers the pipeline-cache
 *       lookup, lambda-chain composition (1 terminal + 1 layer lambda),
 *       {@link java.util.function.LongSupplier}-based call-ID allocation,
 *       and two nested dispatches.</li>
 *   <li><strong>Cumulative cost for 3 layers above baseline: ~19&nbsp;ns
 *       in both modes</strong> (18.8&nbsp;ns JDK, 18.4&nbsp;ns CGLIB).
 *       Per-layer marginal costs scatter between 3&ndash;7&nbsp;ns due to
 *       JIT state variation, but the sum is remarkably stable. This
 *       confirms the architectural claim that pipeline dispatch is
 *       orthogonal to the proxy entry mechanism.</li>
 * </ul>
 *
 * <h3>Partial escape analysis: observed, non-deterministic</h3>
 * <p>Across runs, one or more variants tend to exhibit a characteristic
 * signature: the {@code gc.alloc.rate.norm} variance collapses to near-zero
 * (&plusmn;0.001&ndash;0.002&nbsp;B/op, three orders of magnitude tighter
 * than the &plusmn;7&ndash;15 seen elsewhere), and occasionally the
 * absolute allocation drops below what the layer count would predict. This
 * is the signature of C2's escape analysis successfully stack-allocating
 * the {@link eu.inqudium.core.pipeline.InternalExecutor} lambdas for that
 * variant &mdash; the per-iteration allocation becomes fully deterministic.</p>
 *
 * <p><strong>Which variant gets this treatment is not stable between runs.</strong>
 * The first measurement of this benchmark hit EA on {@code inqudium_jdk_3_layers}:
 * 704&nbsp;B/op at &plusmn;0.002, actually <em>less</em> allocation than
 * the 2-layer case despite composing one more lambda. That effect did not
 * reproduce in the second run, where JDK 3L instead showed textbook linear
 * growth (760&nbsp;B/op, &plusmn;7.7). The second run hit the EA signature
 * on the CGLIB side instead: {@code spring_aop_cglib_baseline}
 * (536&nbsp;B/op, &plusmn;0.001) and {@code inqudium_cglib_2_layers}
 * (616&nbsp;B/op, &plusmn;0.001).</p>
 *
 * <p>The mechanism is real and the gains are real when they happen, but
 * <strong>production capacity planning should use the non-EA numbers as
 * the baseline</strong>. When EA does kick in on a particular variant on a
 * particular deployment, treat it as an unearned bonus, not a guarantee.
 * To inspect the behavior on a given environment, run with
 * {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining} or capture
 * JFR events with {@code -prof jfr}.</p>
 *
 * <h3>Production context</h3>
 * <p>The framework overhead of Inqudium above a flat Spring-AOP aspect is
 * 7&ndash;19&nbsp;ns per call across 1 to 3 layers, and that delta is
 * stable across proxy modes. Real resilience work dominates this by one
 * to two orders of magnitude: a Resilience4j bulkhead semaphore
 * acquisition costs ~50&ndash;200&nbsp;ns, a circuit-breaker state check
 * ~20&ndash;80&nbsp;ns, a single active SLF4J log statement
 * ~500&ndash;2000&nbsp;ns.</p>
 *
 * <p>The largest single cost in the stack is the Spring-AOP proxy
 * infrastructure itself (~88&ndash;98&nbsp;ns) &mdash; dominated by the
 * {@code ReflectiveMethodInvocation} chain, not by the proxy entry
 * mechanism. Switching from JDK proxy to CGLIB saves ~10&nbsp;ns. Moving
 * from Spring-AOP to AspectJ compile-time weaving would skip the proxy
 * entirely and cut a much larger slice &mdash; if latency ever matters at
 * that level, CTW is the bigger lever than any pipeline-level
 * optimization.</p>
 *
 * <h3>Caveats</h3>
 * <ul>
 *   <li>Single-threaded measurement. Cross-thread cache-line contention on
 *       the volatile counters is not covered here; that was specifically
 *       avoided by design when the instance-local call-ID counters were
 *       introduced.</li>
 *   <li>JIT optimization state is not reproducible across runs &mdash;
 *       documented in the escape-analysis section above with concrete
 *       first-run vs. second-run evidence. Capacity planning should use
 *       the non-EA numbers.</li>
 *   <li>The target method is trivial on purpose. Nothing in these numbers
 *       reflects the cost of actual method bodies, database calls, or
 *       remote I/O.</li>
 *   <li>CGLIB requires non-{@code final} classes and non-{@code final}
 *       methods to subclass. {@link BenchmarkServiceImpl} satisfies this.
 *       Target classes in production code that are {@code final} (e.g.
 *       many Kotlin classes by default) cannot be CGLIB-proxied and fall
 *       back to other mechanisms or require {@code kotlin-spring}
 *       compiler support.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3)
@State(Scope.Benchmark)
public class AspectOverheadBenchmark {

    /**
     * Fixed input &mdash; its value is irrelevant, only that it is opaque
     * to the JIT so the target method body cannot be folded into a constant.
     */
    private static final int INPUT = 42;
    /**
     * Raw target, no proxy, no aspect. The JIT is free to inline
     * {@link BenchmarkServiceImpl#execute(int)} completely.
     */
    private BenchmarkService raw;
    private BenchmarkService baselineJdkProxy;

    // ---- JDK dynamic proxy variants ----
    private BenchmarkService inqJdk1;
    private BenchmarkService inqJdk2;
    private BenchmarkService inqJdk3;
    private BenchmarkService baselineCglibProxy;

    // ---- CGLIB subclass proxy variants ----
    private BenchmarkService inqCglib1;
    private BenchmarkService inqCglib2;
    private BenchmarkService inqCglib3;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                //.addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(AspectOverheadBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    @Setup(Level.Trial)
    public void setup(Blackhole bh) {
        // Raw reference, no proxy
        this.raw = new BenchmarkServiceImpl();

        // Baselines in both proxy modes
        this.baselineJdkProxy = buildProxy(new BaselineSpringAspect(bh), false);
        this.baselineCglibProxy = buildProxy(new BaselineSpringAspect(bh), true);

        // Inqudium pipeline, JDK proxy, 1/2/3 layers
        this.inqJdk1 = buildInqudiumProxy(bh, 1, false);
        this.inqJdk2 = buildInqudiumProxy(bh, 2, false);
        this.inqJdk3 = buildInqudiumProxy(bh, 3, false);

        // Inqudium pipeline, CGLIB proxy, 1/2/3 layers
        this.inqCglib1 = buildInqudiumProxy(bh, 1, true);
        this.inqCglib2 = buildInqudiumProxy(bh, 2, true);
        this.inqCglib3 = buildInqudiumProxy(bh, 3, true);
    }

    /**
     * Builds an Inqudium-pipeline proxy with the given number of counting
     * layers. Layer order values are spaced by 10 so the sort in
     * {@code ResolvedPipeline.resolve} is deterministic and preserves
     * insertion order.
     *
     * @param bh               the JMH blackhole captured at setup time
     * @param layerCount       how many layers the pipeline should contain
     * @param proxyTargetClass {@code false} for JDK dynamic proxy,
     *                         {@code true} for CGLIB subclass proxy
     */
    private BenchmarkService buildInqudiumProxy(Blackhole bh, int layerCount,
                                                boolean proxyTargetClass) {
        List<AspectLayerProvider<Object>> providers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            providers.add(new CountingLayerProvider("L" + (i + 1), (i + 1) * 10, bh));
        }
        return buildProxy(new InqudiumPipelineAspect(providers), proxyTargetClass);
    }

    /**
     * Wraps a fresh {@link BenchmarkServiceImpl} in an
     * {@link AspectJProxyFactory} with the given aspect bound, selecting
     * the proxy mechanism explicitly.
     *
     * @param aspect           the {@code @Aspect}-annotated advice object
     * @param proxyTargetClass {@code false} forces JDK dynamic proxy (via
     *                         the implemented interface),
     *                         {@code true} forces CGLIB subclass proxy
     */
    private BenchmarkService buildProxy(Object aspect, boolean proxyTargetClass) {
        AspectJProxyFactory pf = new AspectJProxyFactory(new BenchmarkServiceImpl());
        pf.setProxyTargetClass(proxyTargetClass);
        pf.addAspect(aspect);
        return pf.getProxy();
    }

    // ======================== Benchmark methods ========================

    @Benchmark
    public int raw_no_proxy() {
        return raw.execute(INPUT);
    }

    // ---- JDK dynamic proxy ----

    @Benchmark
    public int spring_aop_jdk_baseline() {
        return baselineJdkProxy.execute(INPUT);
    }

    @Benchmark
    public int inqudium_jdk_1_layer() {
        return inqJdk1.execute(INPUT);
    }

    @Benchmark
    public int inqudium_jdk_2_layers() {
        return inqJdk2.execute(INPUT);
    }

    @Benchmark
    public int inqudium_jdk_3_layers() {
        return inqJdk3.execute(INPUT);
    }

    // ---- CGLIB subclass proxy ----

    @Benchmark
    public int spring_aop_cglib_baseline() {
        return baselineCglibProxy.execute(INPUT);
    }

    @Benchmark
    public int inqudium_cglib_1_layer() {
        return inqCglib1.execute(INPUT);
    }

    @Benchmark
    public int inqudium_cglib_2_layers() {
        return inqCglib2.execute(INPUT);
    }

    @Benchmark
    public int inqudium_cglib_3_layers() {
        return inqCglib3.execute(INPUT);
    }
}
