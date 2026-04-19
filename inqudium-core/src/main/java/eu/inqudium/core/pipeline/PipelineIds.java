package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Single source of monotonically-increasing identifiers for the pipeline
 * framework.
 *
 * <p>Consolidates the previous split across {@code ChainIdGenerator} and
 * {@code StandaloneIdGenerator}, plus the ad-hoc {@code AtomicLong} counters
 * that used to be allocated directly inside {@code ResolvedPipelineState}
 * and {@code AbstractBaseWrapper}. Everything now lives here, behind one
 * coherent API.</p>
 *
 * <h3>Two JVM-global counters + a factory for instance-local counters</h3>
 * <dl>
 *   <dt>{@link #nextChainId() Chain IDs}</dt>
 *   <dd>JVM-global. Drawn once per new wrapper chain and once per resolved
 *       pipeline. Every layer wrapping the same delegate inherits the same
 *       chain ID. Also used by standalone executions so that their IDs
 *       cannot collide with any chain created in the same JVM. Call rate
 *       is low (once per long-lived pipeline or chain construction) —
 *       contention is a non-issue.</dd>
 *
 *   <dt>{@link #nextStandaloneCallId() Standalone call IDs}</dt>
 *   <dd>JVM-global. Drawn by one-shot execution paths that are not backed
 *       by a long-lived pipeline — typically {@code InqExecutor} and
 *       {@code InqAsyncExecutor}. One-shot usage keeps call rate per caller
 *       low; aggregate contention across callers is accepted as a cost
 *       of having no persistent state to attach a counter to.</dd>
 *
 *   <dt>{@link #newInstanceCallIdSource() Instance-local call-ID source}</dt>
 *   <dd>Factory. Produces a fresh {@link LongSupplier} backed by its own
 *       {@link AtomicLong}. Used by resolved pipelines and wrapper chains
 *       that keep per-instance call counts, so that threads hammering
 *       independent pipelines do not contend on a single counter.
 *       <strong>This is the hot-path counter</strong> — instance-local
 *       partitioning is what keeps per-call overhead near the uncontended
 *       ~5–10 ns region under load, instead of degrading to 100+ ns per
 *       op as a shared counter would at high concurrency.</dd>
 * </dl>
 *
 * <h3>Why instance-local instead of JVM-global for per-pipeline counters</h3>
 * <p>A single shared {@code AtomicLong} incremented from many threads
 * generates cache-line contention: the {@code LOCK XADD} instruction
 * forces the owning cache line to migrate between cores, and at high
 * throughput this migration cost dominates the hot path. Each pipeline
 * having its own counter means threads calling different pipelines do
 * not contend; contention is bounded to the (typically small) set of
 * threads actually sharing a single pipeline. This is the same
 * partitioning principle that {@link java.util.concurrent.atomic.LongAdder}
 * applies internally — we just get it "for free" by following the pipeline
 * structure.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All counters use {@link AtomicLong}. Every {@code next…Id()} call is
 * lock-free and completes in a single CAS. IDs are monotonically increasing
 * and never reused within a JVM lifetime.</p>
 *
 * <p>This class is a stateless utility holder — it cannot be instantiated.</p>
 *
 * @since 0.8.0
 */
public final class PipelineIds {

    /**
     * JVM-wide counter for chain IDs.
     */
    private static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

    /**
     * JVM-wide counter for standalone call IDs.
     */
    private static final AtomicLong STANDALONE_CALL_ID_COUNTER = new AtomicLong();

    /**
     * Prevent instantiation — this is a utility class.
     */
    private PipelineIds() {
    }

    /**
     * Generates the next globally unique chain ID.
     *
     * <p>Returned IDs are monotonically increasing and never collide within
     * the same JVM lifetime, regardless of whether they are requested by a
     * wrapper chain, a resolved pipeline, or a standalone execution.</p>
     *
     * @return a new, unique chain ID
     */
    public static long nextChainId() {
        return CHAIN_ID_COUNTER.incrementAndGet();
    }

    /**
     * Generates the next globally unique standalone call ID.
     *
     * <p>Intended for one-shot executions that are not tied to a long-lived
     * pipeline. Pipeline-backed executions should use an instance-local
     * source obtained from {@link #newInstanceCallIdSource()} instead — that
     * avoids contention on a single shared counter under concurrent load.</p>
     *
     * @return a new, unique standalone call ID
     */
    public static long nextStandaloneCallId() {
        return STANDALONE_CALL_ID_COUNTER.incrementAndGet();
    }

    /**
     * Creates a fresh instance-local call-ID source.
     *
     * <p>Each call returns a new {@link LongSupplier} backed by its own
     * private {@link AtomicLong}. Callers should hold the returned supplier
     * as a field and invoke {@link LongSupplier#getAsLong()} to obtain
     * successive call IDs — the returned values are monotonically increasing
     * within that supplier, starting at 1.</p>
     *
     * <p>Use this factory whenever the call-ID counter should be partitioned
     * per pipeline or per wrapper chain (i.e. almost always, on the hot
     * path). The instance-local partitioning eliminates cache-line contention
     * that a shared global counter would exhibit under multi-threaded load.</p>
     *
     * <p>When a wrapper chain is extended (outer wrapper around an existing
     * wrapper), the outer layer should <em>inherit</em> the inner layer's
     * existing supplier rather than creating a new one — this keeps call
     * IDs consistent across all layers of the same chain. New chains (and
     * new resolved pipelines) get a fresh supplier here.</p>
     *
     * @return a new, independent call-ID source
     */
    public static LongSupplier newInstanceCallIdSource() {
        AtomicLong counter = new AtomicLong();
        return counter::incrementAndGet;
    }
}
