package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.integration.perlayer.Authorized;
import eu.inqudium.aspect.pipeline.integration.perlayer.Logged;
import eu.inqudium.aspect.pipeline.integration.perlayer.Timed;

import java.util.function.IntConsumer;

/**
 * Service with identical method bodies but different annotation combinations.
 * Used by {@code WovenPipelineBenchmark} to measure pure pipeline overhead.
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

    private final IntConsumer consumeCPU;


    public BenchmarkService(IntConsumer consumeCPU) {
        this.consumeCPU = consumeCPU;
    }

    // ======================== Baseline ========================

    /** No annotations — not intercepted. Performance floor. */
    public String baseline() {
        consumeCPU.accept(50);
        return "baseline";
    }

    // ======================== Single-annotation pattern ========================

    /** @Resilient → ResilienceAspect: AUTH → LOG → TIMING (3 layers). */
    @Resilient
    public String resilient3Layers() {
        consumeCPU.accept(50);
        return "resilient-3";
    }

    // ======================== Per-layer annotation pattern ========================

    /** @Authorized @Logged @Timed → PerLayerAspect: AUTH → LOG → TIME (3 layers). */
    @Authorized
    @Logged
    @Timed
    public String perLayer3() {
        consumeCPU.accept(50);
        return "perLayer-3";
    }

    /** @Authorized @Logged → PerLayerAspect: AUTH → LOG (2 layers). */
    @Authorized @Logged
    public String perLayer2() {
        consumeCPU.accept(50);
        return "perLayer-2";
    }

    /** @Logged → PerLayerAspect: LOG (1 layer). */
    @Logged
    public String perLayer1() {
        consumeCPU.accept(50);
        return "perLayer-1";
    }
}
