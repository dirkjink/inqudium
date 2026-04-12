package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.integration.Resilient;

/**
 * Service with identical method bodies but different annotation combinations.
 * Used by {@code WovenPipelineBenchmark} to measure pure pipeline overhead.
 *
 * <p>Each method calls {@link #consumeTokens(int)} with the same token count,
 * ensuring that benchmark deltas reflect only the pipeline overhead — not
 * differences in the method body.</p>
 *
 * <p>This class lives in {@code src/main/java} so that it is woven during
 * the {@code compile} goal (not {@code test-compile}), avoiding classpath
 * conflicts with JMH's annotation processor.</p>
 */
public class BenchmarkService {

    /**
     * Volatile field used as a spin-loop sink to prevent JIT elimination.
     * Equivalent to {@code Blackhole.consumeCPU()} but without a JMH dependency.
     */
    @SuppressWarnings("unused")
    private static volatile long sink;

    /**
     * Consumes CPU by running a deterministic spin loop.
     * The volatile write prevents the JIT from eliminating the loop.
     *
     * @param tokens the number of loop iterations (roughly equivalent to
     *               {@code Blackhole.consumeCPU(tokens)})
     */
    public static void consumeTokens(int tokens) {
        long s = sink;
        for (int i = 0; i < tokens; i++) {
            s += i;
        }
        sink = s;
    }

    // ======================== Baseline ========================

    /** No annotations — not intercepted. Performance floor. */
    public String baseline() {
        consumeTokens(50);
        return "baseline";
    }

    // ======================== Single-annotation pattern ========================

    /** @Resilient → ResilienceAspect: AUTH → LOG → TIMING (3 layers). */
    @Resilient
    public String resilient3Layers() {
        consumeTokens(50);
        return "resilient-3";
    }

    // ======================== Per-layer annotation pattern ========================

    /** @Authorized @Logged @Timed → PerLayerAspect: AUTH → LOG → TIME (3 layers). */
    @Authorized @Logged @Timed
    public String perLayer3() {
        consumeTokens(50);
        return "perLayer-3";
    }

    /** @Authorized @Logged → PerLayerAspect: AUTH → LOG (2 layers). */
    @Authorized @Logged
    public String perLayer2() {
        consumeTokens(50);
        return "perLayer-2";
    }

    /** @Logged → PerLayerAspect: LOG (1 layer). */
    @Logged
    public String perLayer1() {
        consumeTokens(50);
        return "perLayer-1";
    }
}
