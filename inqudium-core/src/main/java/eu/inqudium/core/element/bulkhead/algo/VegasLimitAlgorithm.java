package eu.inqudium.core.element.bulkhead.algo;

import eu.inqudium.core.algo.ContinuousTimeEwma;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A latency-based adaptive limit algorithm inspired by TCP Vegas congestion control.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Unlike {@link AimdLimitAlgorithm}, which is <b>reactive</b> and waits for explicit errors
 * (timeouts, 5xx responses) to reduce the limit, Vegas is <b>proactive</b>. It continuously
 * monitors request latency and detects queuing delay in the downstream service <em>before</em>
 * failures occur. The core insight is that rising latency is an early warning signal of
 * congestion — if the downstream service starts taking longer to respond, it is likely
 * building up an internal queue, and the caller should back off before that queue overflows
 * into errors.
 *
 * <h2>The Three Pillars of Vegas</h2>
 *
 * <h3>1. No-Load RTT (Baseline)</h3>
 * <p>The algorithm maintains an estimate of the <b>best-case response time</b> — the physical
 * minimum time required when the downstream system has zero queued requests. This represents
 * the inherent processing latency without any queuing overhead.
 *
 * <p>The baseline is initialized to {@link Long#MAX_VALUE} (unknown) and converges toward
 * reality as samples arrive. It tracks the minimum observed RTT, but with a configurable
 * <b>time-based decay</b> that slowly drifts it upward toward the smoothed RTT.
 * Without decay, a single artificially low measurement (e.g., a cached response, a GC
 * pause that delayed the timestamp before the call but not after) would permanently poison
 * the baseline, causing the gradient to chronically overestimate congestion.
 *
 * <h3>2. Continuous-Time Smoothed Current RTT (EWMA)</h3>
 * <p>The algorithm calculates an Exponentially Weighted Moving Average of recent response
 * times using a continuous-time approach (via {@link ContinuousTimeEwma}). This filters out
 * random latency spikes (GC pauses, network jitter) and provides a stable "current conditions"
 * signal that is completely independent of the request rate (RPS).
 *
 * <h3>3. The Gradient</h3>
 * <p>The ratio {@code noLoadRtt / smoothedRtt} is the gradient — the key signal that drives
 * all limit adjustments:
 * <ul>
 * <li>{@code gradient ≈ 1.0}: The current RTT matches the baseline. No queuing detected,
 * the downstream system is operating at its physical minimum. The limit can safely
 * increase.</li>
 * <li>{@code gradient = 0.5}: The current RTT is twice the baseline. The downstream system
 * is spending roughly half its time processing and half queuing. The limit should
 * decrease.</li>
 * <li>{@code gradient > 1.0}: The current RTT is actually faster than the baseline. This
 * can happen when the baseline was initially set during a warm-up period with higher
 * latency. Capped at 1.2 to prevent runaway growth.</li>
 * </ul>
 *
 * <p>The new limit is then: {@code newLimit = currentLimit × gradient + probingFactor}
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state is bundled into a single immutable {@link VegasState} record and managed via
 * {@link AtomicReference#compareAndSet(Object, Object)}. This guarantees that every
 * {@link #update(Duration, boolean)} call reads and writes a consistent snapshot without
 * any locking or blocking.
 *
 * @see AimdLimitAlgorithm
 * @see ContinuousTimeEwma
 * @see InqLimitAlgorithm
 * @since 0.2.0
 */
public final class VegasLimitAlgorithm implements InqLimitAlgorithm {

    // ──────────────────────────────────────────────────────────────────────────
    // Configuration Fields (immutable after construction)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The absolute minimum concurrency limit. The algorithm will never reduce the limit
     * below this floor, regardless of how severe the detected congestion is.
     */
    private final int minLimit;

    /**
     * The absolute maximum concurrency limit. The algorithm will never increase the limit
     * above this ceiling, even if the gradient indicates zero congestion.
     */
    private final int maxLimit;

    /**
     * The stateless calculator used to compute the continuous-time EWMA of the current RTT.
     * Replaces the manual request-based smoothing factor to ensure RPS-independent decay.
     */
    private final ContinuousTimeEwma rttSmoothingEwma;

    /**
     * The stateless calculator used to compute the continuous-time drift of the baseline
     * towards the smoothed RTT. Can be null if decay is disabled.
     */
    private final ContinuousTimeEwma baselineDriftEwma;

    /**
     * The stateless calculator used to compute the continuous-time EWMA of the error rate.
     * Uses a separate time constant from RTT smoothing, because error rates should be
     * smoothed over a longer period to avoid overreacting to isolated failures.
     */
    private final ContinuousTimeEwma errorRateEwma;

    /**
     * The EWMA-smoothed error rate must strictly exceed this threshold before the
     * reactive multiplicative decrease (fallback) is triggered.
     */
    private final double errorRateThreshold;

    /**
     * The minimum utilization percentage (0.0 to 1.0) required to allow limit growth.
     * Prevents limit inflation when the system is under low load — without this check,
     * the gradient would indicate "no congestion" simply because few requests are active,
     * causing the limit to drift toward maxLimit without ever testing real capacity.
     */
    private final double minUtilizationThreshold;

    /**
     * The injectable nano-time source for all timing measurements related to the EWMA decay.
     */
    private final LongSupplier nanoTimeSource;

    // ──────────────────────────────────────────────────────────────────────────
    // Mutable State (managed via CAS on an immutable snapshot)
    // ──────────────────────────────────────────────────────────────────────────

    private final AtomicReference<VegasState> state;

    // ──────────────────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new Vegas limit algorithm with the default baseline drift.
     *
     * <p>Backward-compatible constructor. Uses a 10-second baseline drift time constant,
     * the same error rate smoothing time constant as RTT smoothing, and no utilization
     * threshold (0.0). For production deployments, prefer the 9-parameter constructor
     * which allows independent tuning of error rate smoothing and utilization thresholds.
     *
     * @param initialLimit          The starting concurrency limit before any RTT feedback is
     *                              received. Clamped to [{@code minLimit}, {@code maxLimit}].
     * @param minLimit              The absolute minimum concurrency. Clamped to at least 1.
     * @param maxLimit              The absolute maximum concurrency. Clamped to at least
     *                              {@code minLimit}.
     * @param smoothingTimeConstant The time constant (Tau) for RTT smoothing. A larger
     *                              duration means the average reacts more slowly and ignores short spikes.
     * @param errorRateThreshold    Threshold (0.0-1.0) for the smoothed error rate fallback.
     * @param nanoTimeSource        The time source used for calculating elapsed time.
     */
    public VegasLimitAlgorithm(int initialLimit,
                               int minLimit,
                               int maxLimit,
                               Duration smoothingTimeConstant,
                               double errorRateThreshold,
                               LongSupplier nanoTimeSource) {
        this(initialLimit,
                minLimit,
                maxLimit,
                smoothingTimeConstant,
                Duration.ofSeconds(10),
                errorRateThreshold,
                nanoTimeSource);
    }

    /**
     * Creates a new Vegas limit algorithm with full control over baseline decay behavior.
     *
     * <p>Backward-compatible constructor. Uses the same time constant for error rate
     * smoothing as for RTT smoothing (original behavior). For production deployments that
     * need a slower, more stable error rate, use the 9-parameter constructor with a
     * separate {@code errorRateSmoothingTimeConstant}.
     *
     * <p><b>Recommended production configuration:</b>
     * <pre>{@code
     * new VegasLimitAlgorithm(
     * 50,    // initialLimit: conservative starting point
     * 5,     // minLimit: always allow probe requests
     * 200,   // maxLimit: prevent resource exhaustion
     * Duration.ofMillis(500),  // smoothingTimeConstant: filters jitter well
     * Duration.ofSeconds(10),  // baselineDriftTimeConstant: slow but safe recovery
     * Duration.ofSeconds(5),   // errorRateSmoothingTimeConstant: absorb isolated errors
     * 0.05,                    // errorRateThreshold: 5% error rate triggers fallback
     * 0.6,                     // minUtilizationThreshold: 60% utilization needed to grow
     * System::nanoTime         // nanoTimeSource
     * );
     * }</pre>
     *
     * @param initialLimit              The starting concurrency limit. Clamped to
     *                                  [{@code minLimit}, {@code maxLimit}].
     * @param minLimit                  The absolute minimum concurrency. Clamped to at least 1.
     * @param maxLimit                  The absolute maximum concurrency. Clamped to at least
     *                                  {@code minLimit}.
     * @param smoothingTimeConstant     The time constant (Tau) for continuous-time RTT smoothing.
     * @param baselineDriftTimeConstant The time constant (Tau) at which the no-load baseline
     *                                  drifts toward the smoothed RTT. A null or zero duration
     *                                  disables decay entirely.
     * @param errorRateThreshold        Threshold (0.0-1.0) for the smoothed error rate fallback.
     * @param nanoTimeSource            The time source used for calculating elapsed time.
     */
    public VegasLimitAlgorithm(int initialLimit,
                               int minLimit,
                               int maxLimit,
                               Duration smoothingTimeConstant,
                               Duration baselineDriftTimeConstant,
                               double errorRateThreshold,
                               LongSupplier nanoTimeSource) {
        // Backward compatibility: error rate uses the same time constant as RTT smoothing.
        // This preserves the original behavior where both EWMAs shared the same Tau.
        // Default utilization threshold: 0.0 (disabled) for backward compatibility.
        this(initialLimit, minLimit, maxLimit, smoothingTimeConstant,
                baselineDriftTimeConstant,
                smoothingTimeConstant,
                errorRateThreshold, 0.0, nanoTimeSource);
    }

    /**
     * Creates a new Vegas limit algorithm with full control over all behavioral parameters.
     *
     * @param initialLimit                   The starting concurrency limit. Clamped to
     *                                       [{@code minLimit}, {@code maxLimit}].
     * @param minLimit                       The absolute minimum concurrency. Clamped to at least 1.
     * @param maxLimit                       The absolute maximum concurrency. Clamped to at least
     *                                       {@code minLimit}.
     * @param smoothingTimeConstant          The time constant (Tau) for continuous-time RTT smoothing.
     * @param baselineDriftTimeConstant      The time constant (Tau) at which the no-load baseline
     *                                       drifts toward the smoothed RTT. A null or zero duration
     *                                       disables decay entirely.
     * @param errorRateSmoothingTimeConstant The time constant (Tau) for smoothing the error rate.
     *                                       Should typically be larger than {@code smoothingTimeConstant}
     *                                       to avoid overreacting to isolated failures.
     * @param errorRateThreshold             Threshold (0.0-1.0) for the smoothed error rate fallback.
     * @param minUtilizationThreshold        The minimum utilization percentage (0.0 to 1.0) required
     *                                       to allow limit growth. Prevents limit inflation under low load.
     * @param nanoTimeSource                 The time source used for calculating elapsed time.
     */
    public VegasLimitAlgorithm(int initialLimit,
                               int minLimit,
                               int maxLimit,
                               Duration smoothingTimeConstant,
                               Duration baselineDriftTimeConstant,
                               Duration errorRateSmoothingTimeConstant,
                               double errorRateThreshold,
                               double minUtilizationThreshold,
                               LongSupplier nanoTimeSource) {

        this.minLimit = Math.max(1, minLimit);
        this.maxLimit = Math.max(this.minLimit, maxLimit);

        this.rttSmoothingEwma = new ContinuousTimeEwma(smoothingTimeConstant);
        this.errorRateEwma = new ContinuousTimeEwma(errorRateSmoothingTimeConstant);
        this.errorRateThreshold = Math.max(0.0, Math.min(1.0, errorRateThreshold));
        this.minUtilizationThreshold = Math.max(0.0, Math.min(1.0, minUtilizationThreshold));

        if (baselineDriftTimeConstant != null && baselineDriftTimeConstant.toNanos() > 0) {
            this.baselineDriftEwma = new ContinuousTimeEwma(baselineDriftTimeConstant);
        } else {
            this.baselineDriftEwma = null;
        }

        this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;

        double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
        this.state = new AtomicReference<>(new VegasState(Long.MAX_VALUE, 0, bounded, 0.0, this.nanoTimeSource.getAsLong()));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Preset Factory Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Protective</b> preset — prioritizes stability over throughput.
     *
     * <p>Designed for latency-sensitive downstream services where even brief
     * oversaturation causes cascading failures (e.g., payment gateways, auth
     * services, databases with strict connection limits).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Slow RTT smoothing (2 s):</b> Heavily filters latency noise. The gradient
     *       reacts to sustained trends rather than individual spikes, preventing
     *       unnecessary limit oscillation.</li>
     *   <li><b>Very slow baseline drift (30 s):</b> The no-load baseline is highly resistant
     *       to temporary latency shifts, keeping the gradient conservative.</li>
     *   <li><b>Tolerant error fallback (15%):</b> The reactive 0.8× decrease only triggers
     *       when the 10-second EWMA error rate exceeds 15%, absorbing transient error bursts
     *       without capacity loss.</li>
     *   <li><b>Low utilization gate (50%):</b> Allows limit growth at moderate load so the
     *       system can still adapt during ramp-up, while preventing inflation when idle.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Initial limit</td><td>20</td></tr>
     *   <tr><td>Min / Max limit</td><td>1 / 200</td></tr>
     *   <tr><td>RTT smoothing Tau</td><td>2 s</td></tr>
     *   <tr><td>Baseline drift Tau</td><td>30 s</td></tr>
     *   <tr><td>Error rate smoothing Tau</td><td>10 s</td></tr>
     *   <tr><td>Error rate threshold</td><td>15%</td></tr>
     *   <tr><td>Min utilization</td><td>50%</td></tr>
     * </table>
     *
     * @return a protectively tuned Vegas algorithm
     */
    public static VegasLimitAlgorithm protective() {
        return new VegasLimitAlgorithm(
                20,                        // initialLimit: conservative starting point
                1,                         // minLimit: always allow at least one probe
                200,                       // maxLimit: hard ceiling prevents runaway scaling
                Duration.ofSeconds(2),     // smoothingTimeConstant: heavy noise filtering
                Duration.ofSeconds(30),    // baselineDriftTimeConstant: very slow — resists transient shifts
                Duration.ofSeconds(10),    // errorRateSmoothingTimeConstant: absorbs transient error bursts
                0.15,                      // errorRateThreshold: tolerant — 15% before reactive fallback
                0.5,                       // minUtilizationThreshold: moderate gate
                System::nanoTime
        );
    }

    /**
     * <b>Balanced</b> preset — the recommended production default.
     *
     * <p>Suitable for most backend-to-backend communication where the downstream
     * service has moderate, somewhat predictable capacity and latency characteristics
     * (e.g., internal microservices, managed databases, message brokers).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Responsive RTT smoothing (1 s):</b> Adapts to latency changes within seconds
     *       while still filtering single-request jitter.</li>
     *   <li><b>Moderate baseline drift (10 s):</b> The no-load baseline slowly tracks reality,
     *       recovering from stale low outliers within tens of seconds.</li>
     *   <li><b>Balanced error fallback (10%):</b> The 5-second EWMA window detects sustained
     *       failure patterns within a few seconds without overreacting to isolated errors.</li>
     *   <li><b>Utilization gate (60%):</b> Prevents limit inflation during off-peak periods
     *       while allowing growth once load is meaningful.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Initial limit</td><td>50</td></tr>
     *   <tr><td>Min / Max limit</td><td>5 / 500</td></tr>
     *   <tr><td>RTT smoothing Tau</td><td>1 s</td></tr>
     *   <tr><td>Baseline drift Tau</td><td>10 s</td></tr>
     *   <tr><td>Error rate smoothing Tau</td><td>5 s</td></tr>
     *   <tr><td>Error rate threshold</td><td>10%</td></tr>
     *   <tr><td>Min utilization</td><td>60%</td></tr>
     * </table>
     *
     * @return a balanced Vegas algorithm suitable for general production use
     */
    public static VegasLimitAlgorithm balanced() {
        return new VegasLimitAlgorithm(
                50,                        // initialLimit: moderate starting point
                5,                         // minLimit: keeps a small probe window open
                500,                       // maxLimit: allows significant scaling headroom
                Duration.ofSeconds(1),     // smoothingTimeConstant: responsive yet stable
                Duration.ofSeconds(10),    // baselineDriftTimeConstant: moderate drift speed
                Duration.ofSeconds(5),     // errorRateSmoothingTimeConstant: balanced error detection
                0.1,                       // errorRateThreshold: 10% triggers reactive fallback
                0.6,                       // minUtilizationThreshold: prevents low-load inflation
                System::nanoTime
        );
    }

    /**
     * <b>Performant</b> preset — prioritizes throughput over caution.
     *
     * <p>Designed for downstream services with high, elastic capacity where
     * under-utilization is more costly than brief oversaturation (e.g., autoscaling
     * compute clusters, CDN origins, horizontally scaled stateless services).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Fast RTT smoothing (500 ms):</b> The gradient reacts quickly to latency
     *       changes, enabling rapid upward scaling when conditions improve.</li>
     *   <li><b>Fast baseline drift (5 s):</b> The no-load baseline tracks reality
     *       aggressively, keeping the gradient accurate even during rapid capacity changes.</li>
     *   <li><b>Strict error fallback (5%):</b> Compensates for the fast scaling by
     *       triggering the reactive 0.8× decrease early. The 3-second EWMA ensures
     *       the reaction is still based on a pattern, not a single failure.</li>
     *   <li><b>High utilization gate (75%):</b> Demands clear evidence of load before
     *       allowing limit growth, ensuring the fast scaling is backed by real demand.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Initial limit</td><td>100</td></tr>
     *   <tr><td>Min / Max limit</td><td>10 / 1000</td></tr>
     *   <tr><td>RTT smoothing Tau</td><td>500 ms</td></tr>
     *   <tr><td>Baseline drift Tau</td><td>5 s</td></tr>
     *   <tr><td>Error rate smoothing Tau</td><td>3 s</td></tr>
     *   <tr><td>Error rate threshold</td><td>5%</td></tr>
     *   <tr><td>Min utilization</td><td>75%</td></tr>
     * </table>
     *
     * @return a throughput-optimized Vegas algorithm
     */
    public static VegasLimitAlgorithm performant() {
        return new VegasLimitAlgorithm(
                100,                       // initialLimit: high starting point
                10,                        // minLimit: substantial floor for elastic backends
                1000,                      // maxLimit: generous ceiling
                Duration.ofMillis(500),    // smoothingTimeConstant: fast latency tracking
                Duration.ofSeconds(5),     // baselineDriftTimeConstant: fast — tracks reality aggressively
                Duration.ofSeconds(3),     // errorRateSmoothingTimeConstant: quick but pattern-based
                0.05,                      // errorRateThreshold: strict — react at 5%
                0.75,                      // minUtilizationThreshold: only grow when truly loaded
                System::nanoTime
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // InqLimitAlgorithm Interface
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public int getLimit() {
        // Add a tiny epsilon (1e-9) to counteract IEEE 754 floating-point precision loss.
        return (int) (state.get().currentLimit() + 1e-9);
    }

    @Override
    public void update(long rttNanos, boolean isSuccess, int inFlightCalls) {

        // Guard: ignore degenerate RTT values.
        if (rttNanos <= 0) return;

        long now = nanoTimeSource.getAsLong();

        VegasState current;
        VegasState next;
        do {
            current = state.get();

            // ── Step 0: Update the Continuous-Time EWMA-smoothed error rate ──
            //
            // Track recent failures to stabilize the reactive fallback.
            double errorSample = isSuccess ? 0.0 : 1.0;
            double newErrorRate = errorRateEwma.calculate(
                    current.smoothedErrorRate(),
                    current.lastUpdateNanos(),
                    now,
                    errorSample
            );

            final long newSmoothed;
            if (isSuccess) {
                // ── Step 1: Update the Smoothed Current RTT (Continuous-Time EWMA) ──
                //
                // The smoothed RTT filters out random latency noise. The calculation is delegated
                // to the stateless ContinuousTimeEwma calculator, ensuring the decay is applied
                // perfectly relative to the elapsed time (independent of RPS).
                //
                // This MUST be calculated before the baseline drift (Step 2), so the baseline
                // can drift towards the most accurate, current representation of the average RTT
                // rather than a stale value from before a potential time gap.
                if (current.smoothedRttNanos() == 0) {
                    newSmoothed = rttNanos;
                } else {
                    newSmoothed = (long) rttSmoothingEwma.calculate(
                            (double) current.smoothedRttNanos(),
                            current.lastUpdateNanos(),
                            now,
                            (double) rttNanos
                    );
                }
            } else {
                newSmoothed = current.smoothedRttNanos();
            }

            final long newNoLoad;
            if (isSuccess) {
                // ── Step 2: Maintain the No-Load Baseline with Time-Based Drift ──
                //
                // First, take the minimum of the current baseline and the new sample.
                long candidateNoLoad = Math.min(current.noLoadRttNanos(), rttNanos);

                // If drift is enabled, and we have a valid smoothed RTT, we drift the baseline
                // slowly towards the NEW smoothed RTT using the continuous-time EWMA.
                // This prevents a single artificially low outlier from permanently poisoning
                // the baseline and causing permanent throttling.
                if (baselineDriftEwma != null && newSmoothed > 0
                        && candidateNoLoad < newSmoothed) {

                    long decayed = (long) baselineDriftEwma.calculate(
                            (double) candidateNoLoad,
                            current.lastUpdateNanos(),
                            now,
                            (double) newSmoothed
                    );

                    newNoLoad = Math.min(decayed, newSmoothed);
                } else {
                    newNoLoad = candidateNoLoad;
                }
            } else {
                newNoLoad = current.noLoadRttNanos();
            }

            double newLimit;

            if (isSuccess) {
                // ── Step 3: Compute the Gradient ──
                //
                // The gradient quantifies how much the downstream system is slowing down
                // relative to its best-case performance (baseline / smoothed).
                double gradient = (newSmoothed > 0) ? (double) newNoLoad / (double) newSmoothed : 1.0;
                gradient = Math.max(0.5, Math.min(1.2, gradient));

                // ── Step 4: Calculate the New Concurrency Limit ──
                // ── Proactive Gradient-Based Adjustment ──
                //
                // Only allow the limit to grow if the system is sufficiently utilized.
                // Without this check, low-load scenarios (e.g., 2 active calls at limit 100)
                // would inflate the limit toward maxLimit because latency appears fine —
                // but the real downstream capacity was never tested.
                boolean isFullyUtilized = inFlightCalls >= (current.currentLimit() * minUtilizationThreshold);

                int visibleLimit = (int) (current.currentLimit() + 1e-9);
                double probingFactor = 1.0 / Math.max(1, visibleLimit);

                if (isFullyUtilized) {
                    newLimit = current.currentLimit() * gradient + probingFactor;
                } else {
                    // Low load: allow gradient-based decrease but suppress growth.
                    // If gradient < 1.0, the downstream is congested even at low load — respect that.
                    // If gradient >= 1.0, hold steady instead of inflating.
                    if (gradient < 1.0) {
                        newLimit = current.currentLimit() * gradient + probingFactor;
                    } else {
                        newLimit = current.currentLimit();
                    }
                }
            } else {
                // ── Reactive Failure Fallback ──
                //
                // When a call fails (timeout, exception, 5xx), the RTT value is often
                // unreliable. We only apply a multiplicative decrease if the smoothed
                // error rate indicates sustained congestion rather than transient hiccups.
                if (newErrorRate > errorRateThreshold) {
                    newLimit = current.currentLimit() * 0.8;
                } else {
                    newLimit = current.currentLimit();
                }
            }

            // ── Step 5: Clamp to Configured Bounds ──
            newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

            // Bundle the updated state and the 'now' timestamp into an immutable snapshot.
            next = new VegasState(newNoLoad, newSmoothed, newLimit, newErrorRate, now);

        } while (!state.compareAndSet(current, next));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal State Record
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of the algorithm's mutable state.
     *
     * @param noLoadRttNanos    The no-load baseline in nanoseconds.
     * @param smoothedRttNanos  The continuous-time EWMA-smoothed current RTT in nanoseconds.
     *                          A value of 0 indicates that no samples have been received yet.
     * @param currentLimit      The current concurrency limit as a double to support the
     *                          fractional results of gradient-based scaling.
     * @param smoothedErrorRate The time-smoothed error rate (0.0 to 1.0).
     * @param lastUpdateNanos   The timestamp of the last state update, used by the
     *                          {@link ContinuousTimeEwma} calculators.
     */
    private record VegasState(long noLoadRttNanos, long smoothedRttNanos, double currentLimit, double smoothedErrorRate,
                              long lastUpdateNanos) {
    }
}