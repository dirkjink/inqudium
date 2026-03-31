package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe implementation of the Additive Increase, Multiplicative Decrease (AIMD) algorithm.
 *
 * <h2>Algorithm Overview</h2>
 * <p>AIMD is a feedback-driven concurrency control algorithm, originally made famous by TCP
 * congestion control (RFC 5681). It dynamically adjusts the maximum number of concurrent
 * calls allowed through a bulkhead by observing the outcomes of completed requests:
 *
 * <ul>
 *   <li><b>Additive Increase (Probing Phase):</b> After each successful call, the concurrency
 *       limit is gently increased. This allows the system to probe for available downstream
 *       capacity without risking sudden overload. The increase strategy is configurable:
 *       <ul>
 *         <li><b>Fixed ({@code +1}, default):</b> Each success adds exactly 1 to the limit.
 *             Simple and predictable, but the growth rate is proportional to transaction
 *             volume. A system processing 1000 RPS would increase the limit by 1000/sec,
 *             hitting maxLimit almost instantly.</li>
 *         <li><b>Windowed ({@code +1/currentLimit}, opt-in):</b> Each success adds a fraction
 *             inversely proportional to the current limit. Over one full "congestion window"
 *             of {@code currentLimit} consecutive successes, the net increase is exactly +1.
 *             This matches classic TCP behavior and makes the growth rate independent of
 *             transaction throughput.</li>
 *       </ul>
 *   </li>
 *   <li><b>Multiplicative Decrease (Protection Phase):</b> When the system detects sustained
 *       failures indicating downstream congestion, the limit is multiplied by a fractional
 *       backoff ratio (e.g., 0.5 to halve it). This aggressively sheds load to give the
 *       downstream service breathing room. The failure detection is configurable:
 *       <ul>
 *         <li><b>Immediate (default):</b> Every individual failure triggers a decrease.
 *             Simple but prone to overreaction on transient network hiccups.</li>
 *         <li><b>EWMA-smoothed (opt-in):</b> An Exponentially Weighted Moving Average tracks
 *             the error rate. Only when this smoothed rate exceeds a configurable threshold
 *             (e.g., 10%) is the decrease triggered. Isolated failures are absorbed without
 *             capacity loss.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Together, the additive increase and multiplicative decrease produce the characteristic
 * "sawtooth" pattern: the limit slowly climbs until congestion is detected, drops sharply,
 * and begins climbing again. The sawtooth amplitude and frequency are controlled by the
 * backoff ratio and the increase strategy.
 *
 * <h2>Configuration Modes</h2>
 *
 * <h3>Classic Mode (4-parameter constructor)</h3>
 * <p>Preserves the exact behavior of the original AIMD implementation for full backward
 * compatibility. Uses fixed {@code +1} increase and immediate per-failure decrease.
 * Suitable for low-to-moderate throughput systems or when simplicity is preferred over
 * stability.
 *
 * <h3>Stabilized Mode (7-parameter constructor)</h3>
 * <p>Enables windowed additive increase and EWMA error rate smoothing for production use
 * in high-throughput environments. Key benefits:
 * <ul>
 *   <li>Growth rate is independent of RPS (no runaway limit inflation)</li>
 *   <li>Transient single failures do not cause capacity drops</li>
 *   <li>Sawtooth amplitude is smaller and more predictable</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable algorithm state ({@code currentLimit} and {@code smoothedErrorRate}) is
 * bundled into a single immutable {@link AimdState} record and managed via
 * {@link AtomicReference#compareAndSet(Object, Object)}. This is the same pattern used by
 * {@link VegasLimitAlgorithm} and guarantees that every {@link #update(Duration, boolean)}
 * call reads and writes a consistent snapshot without any locking or blocking.
 *
 * <p>The CAS retry loop is wait-free in practice: the compute step is pure arithmetic
 * with no I/O, and contention is bounded by the number of threads concurrently completing
 * calls through this bulkhead.
 *
 * <h2>Comparison with {@link VegasLimitAlgorithm}</h2>
 * <table>
 *   <tr><th>Aspect</th><th>AIMD</th><th>Vegas</th></tr>
 *   <tr><td>Trigger</td><td>Error-driven (reacts to failures)</td><td>Latency-driven (reacts to queue buildup)</td></tr>
 *   <tr><td>Detection</td><td>Reactive — waits for errors to occur</td><td>Proactive — detects congestion before errors</td></tr>
 *   <tr><td>Stability</td><td>Sawtooth with sharp drops</td><td>Smooth, gradient-based adjustments</td></tr>
 *   <tr><td>Best for</td><td>Systems where errors are a reliable congestion signal</td><td>Systems where latency is a reliable congestion signal</td></tr>
 * </table>
 *
 * @see VegasLimitAlgorithm
 * @see InqLimitAlgorithm
 * @since 0.2.0
 */
public final class AimdLimitAlgorithm implements InqLimitAlgorithm {

  // ──────────────────────────────────────────────────────────────────────────
  // Configuration Fields (immutable after construction)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The absolute minimum concurrency limit. The algorithm will never reduce the limit
   * below this floor, regardless of how many failures occur. This ensures the system
   * always retains enough capacity to send probe requests and eventually recover.
   *
   * <p>Clamped to at least 1 during construction — a limit of 0 would permanently
   * deadlock the bulkhead with no way to ever send a probing request to detect recovery.
   */
  private final int minLimit;

  /**
   * The absolute maximum concurrency limit. The algorithm will never increase the limit
   * above this ceiling, even under a sustained stream of successes. This prevents
   * unbounded resource consumption (threads, connections, memory) on the calling side.
   *
   * <p>Clamped to at least {@code minLimit} during construction to ensure a valid range.
   */
  private final int maxLimit;

  /**
   * The multiplicative factor applied during the decrease phase (0.1–0.9).
   *
   * <p>When a decrease is triggered, the new limit is calculated as:
   * {@code newLimit = currentLimit * backoffRatio}
   *
   * <p>Common values and their effects:
   * <ul>
   *   <li>{@code 0.5}: Halves the limit — aggressive, fast relief, classic TCP default</li>
   *   <li>{@code 0.75}: 25% reduction — moderate, good for services with bursty traffic</li>
   *   <li>{@code 0.9}: 10% reduction — gentle, suitable when errors are noisy</li>
   * </ul>
   *
   * <p>Lower values provide faster relief to a struggling downstream service but require
   * more time to recover the lost capacity through additive increase.
   */
  private final double backoffRatio;

  /**
   * The EWMA (Exponentially Weighted Moving Average) smoothing factor for the error rate.
   * Controls how much weight each new sample (success=0.0, failure=1.0) carries in the
   * running average.
   *
   * <p>The EWMA formula is: {@code newRate = oldRate * (1 - alpha) + sample * alpha}
   *
   * <p>Effect of different values:
   * <ul>
   *   <li>{@code 0.01}: Very smooth — a single failure barely moves the rate. Requires many
   *       consecutive failures before the threshold is breached. Best for high-throughput
   *       systems where transient errors are common and harmless.</li>
   *   <li>{@code 0.1}: Moderate smoothing (recommended for production). A single failure
   *       shifts the rate by 10%. ~7 consecutive failures push the rate above 0.5.</li>
   *   <li>{@code 0.5}: Responsive — the rate reacts quickly to bursts. Useful when fast
   *       reaction to sudden degradation is more important than smoothness.</li>
   *   <li>{@code 1.0}: No smoothing at all — each sample fully overwrites the previous
   *       rate. After a success, errorRate=0.0; after a failure, errorRate=1.0. This
   *       reproduces the original per-call decrease behavior and is used by the
   *       backward-compatible 4-parameter constructor.</li>
   * </ul>
   *
   * <p>Clamped to [0.01, 1.0] during construction.
   */
  private final double smoothingFactor;

  /**
   * The EWMA-smoothed error rate must strictly exceed this threshold before the
   * multiplicative decrease is triggered.
   *
   * <p>This acts as a noise filter: isolated transient failures push the smoothed error
   * rate upward, but as long as it stays at or below this threshold, the limit remains
   * unchanged. Only a sustained pattern of failures — enough to push the smoothed rate
   * above the threshold — triggers a capacity reduction.
   *
   * <p>Effect of different values:
   * <ul>
   *   <li>{@code 0.0}: Any failure with a non-zero error rate triggers decrease. Combined
   *       with {@code smoothingFactor=1.0}, this reproduces the original behavior where
   *       every individual failure immediately triggers a decrease.</li>
   *   <li>{@code 0.1}: A sustained 10% error rate is required before backing off.
   *       Absorbs sporadic timeouts in noisy network environments.</li>
   *   <li>{@code 0.3}: Tolerates up to 30% errors before reacting — very conservative,
   *       only suitable when failures are expected to be frequent and benign.</li>
   * </ul>
   *
   * <p>Clamped to [0.0, 1.0] during construction.
   */
  private final double errorRateThreshold;

  /**
   * Controls the additive increase strategy.
   *
   * <ul>
   *   <li>{@code false} (default): Fixed increase of {@code +1} per successful call.
   *       The limit growth rate is directly proportional to transaction throughput:
   *       at 1000 RPS, the limit increases by ~1000/sec. Simple but unstable at high load.</li>
   *   <li>{@code true}: Windowed increase of {@code +1/currentLimit} per successful call.
   *       Over exactly one "congestion window" of {@code currentLimit} consecutive successes,
   *       the net increase is +1. At limit=100, each success adds 0.01 — the growth rate
   *       is independent of RPS. This matches TCP congestion avoidance (RFC 5681 §3.1).</li>
   * </ul>
   *
   * <p><b>Why windowed increase matters:</b> Consider a system at limit=50 processing
   * 1000 RPS. With fixed +1, the limit reaches maxLimit (say, 200) in 0.15 seconds.
   * The first failure then slashes it to 100 (at backoffRatio=0.5), and it climbs back
   * in 0.1 seconds — creating violent, high-frequency oscillations. With windowed +1/50,
   * the same climb takes 7.5 seconds (150 increments × 50 calls each), producing a much
   * smoother, lower-frequency sawtooth.
   */
  private final boolean windowedIncrease;

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (managed via CAS on an immutable snapshot)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The atomic reference holding the current algorithm state. All reads and writes go
   * through this single reference using compare-and-set, ensuring that the limit and
   * error rate are always read and written as a consistent pair.
   *
   * <p>The alternative — two separate {@code AtomicInteger}/{@code AtomicLong} fields — would
   * allow interleaved reads where one thread sees the limit from state A and the error rate
   * from state B, potentially producing erratic adjustments. The single-reference CAS pattern
   * eliminates this class of bugs entirely.
   *
   * @see AimdState
   */
  private final AtomicReference<AimdState> state;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructors
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new AIMD algorithm in <b>classic mode</b> (backward-compatible).
   *
   * <p>This constructor preserves the exact behavior of the original, pre-refactor
   * implementation:
   * <ul>
   *   <li>Each successful call increases the limit by exactly {@code +1}.</li>
   *   <li>Each failed call immediately triggers a multiplicative decrease by
   *       {@code backoffRatio}, with no smoothing or threshold filtering.</li>
   * </ul>
   *
   * <p>Internally, this delegates to the full constructor with parameters chosen to
   * reproduce the classic semantics:
   * <ul>
   *   <li>{@code smoothingFactor=1.0}: Each EWMA sample fully overwrites the error rate.
   *       After a success the rate is 0.0, after a failure it is 1.0 — no memory of
   *       previous samples, no smoothing effect.</li>
   *   <li>{@code errorRateThreshold=0.0}: The decrease triggers whenever the error rate is
   *       strictly above 0.0. Since a single failure sets the rate to 1.0 (due to
   *       smoothingFactor=1.0), every failure immediately triggers the decrease.</li>
   *   <li>{@code windowedIncrease=false}: Fixed +1 per success, not TCP-style windowed.</li>
   * </ul>
   *
   * @param initialLimit The starting concurrency limit before any feedback is received.
   *                     Clamped to [{@code minLimit}, {@code maxLimit}] if out of range.
   * @param minLimit     The absolute minimum limit. Clamped to at least 1 to ensure the
   *                     system can always send probe requests for recovery.
   * @param maxLimit     The absolute upper bound to prevent infinite scaling. Clamped to
   *                     at least {@code minLimit} to ensure a valid range.
   * @param backoffRatio The multiplier for the decrease phase (e.g., 0.5 for halving).
   *                     Clamped to [0.1, 0.9] to prevent degenerate configurations
   *                     (0.0 would collapse to minLimit, 1.0 would disable decrease).
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double backoffRatio) {
    this(initialLimit, minLimit, maxLimit, backoffRatio, 1.0, 0.0, false);
  }

  /**
   * Creates a new AIMD algorithm in <b>stabilized mode</b> with full control over all
   * behavioral parameters.
   *
   * <p><b>Recommended production configuration:</b>
   * <pre>{@code
   * new AimdLimitAlgorithm(
   *     50,     // initialLimit: start with 50 concurrent calls
   *     5,      // minLimit: always allow at least 5 probe requests
   *     200,    // maxLimit: never exceed 200 concurrent calls
   *     0.5,    // backoffRatio: halve the limit on sustained congestion
   *     0.1,    // smoothingFactor: slow EWMA, absorbs transient spikes
   *     0.1,    // errorRateThreshold: 10% sustained error rate triggers decrease
   *     true    // windowedIncrease: TCP-style +1/cwnd, RPS-independent growth
   * );
   * }</pre>
   *
   * <p><b>Parameter interaction guide:</b>
   * <ul>
   *   <li>To reproduce classic behavior exactly: {@code smoothingFactor=1.0},
   *       {@code errorRateThreshold=0.0}, {@code windowedIncrease=false}.</li>
   *   <li>For maximum stability: low {@code smoothingFactor} (0.05), moderate
   *       {@code errorRateThreshold} (0.15), {@code windowedIncrease=true}.</li>
   *   <li>For fast reaction to real outages: higher {@code smoothingFactor} (0.3),
   *       low {@code errorRateThreshold} (0.05), {@code windowedIncrease=true}.</li>
   * </ul>
   *
   * @param initialLimit       The starting concurrency limit. Clamped to
   *                           [{@code minLimit}, {@code maxLimit}].
   * @param minLimit           The absolute minimum limit. Clamped to at least 1.
   * @param maxLimit           The absolute upper bound. Clamped to at least {@code minLimit}.
   * @param backoffRatio       The decrease multiplier. Clamped to [0.1, 0.9].
   * @param smoothingFactor    EWMA alpha for the error rate. Clamped to [0.01, 1.0].
   *                           Lower values = smoother (more resistant to transient spikes).
   *                           A value of 1.0 disables smoothing entirely.
   * @param errorRateThreshold The smoothed error rate must strictly exceed this value to
   *                           trigger a multiplicative decrease. Clamped to [0.0, 1.0].
   *                           A value of 0.0 triggers on any failure (with non-zero rate).
   * @param windowedIncrease   {@code true} for TCP-style {@code +1/currentLimit} increase,
   *                           {@code false} for classic {@code +1} increase.
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit,
                            double backoffRatio, double smoothingFactor,
                            double errorRateThreshold, boolean windowedIncrease) {

    // Clamp all parameters to their valid ranges to prevent degenerate configurations.
    // This is a deliberate design choice: rather than throwing on invalid input, we
    // silently clamp to the nearest valid value. The rationale is that bulkhead
    // configuration often comes from external config files, and a slightly out-of-range
    // value should not crash the application at startup.
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.backoffRatio = Math.max(0.1, Math.min(0.9, backoffRatio));
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));
    this.errorRateThreshold = Math.max(0.0, Math.min(1.0, errorRateThreshold));
    this.windowedIncrease = windowedIncrease;

    // Clamp the initial limit to the valid [minLimit, maxLimit] range and initialize
    // the algorithm state with a zero error rate (optimistic start — no failures seen).
    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new AimdState(bounded, 0.0));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // InqLimitAlgorithm Interface
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the current concurrency limit as an integer.
   *
   * <p>Internally, the limit is stored as a {@code double} to support fractional
   * increments from the windowed increase strategy ({@code +1/currentLimit}). The
   * truncation to {@code int} means the visible limit changes only when enough
   * fractional increments accumulate to cross an integer boundary. For example,
   * at limit=100.0 with windowed increase, it takes 100 consecutive successes
   * (each adding 0.01) before {@code getLimit()} returns 101.
   *
   * @return the current limit, always in [{@code minLimit}, {@code maxLimit}]
   */
  @Override
  public int getLimit() {
    // Add a tiny epsilon (1e-9) to counteract IEEE 754 floating-point precision loss.
    // Even with a fixed integer denominator, summing fractions like 0.1 ten times in
    // binary floating-point arithmetic results in 10.999999999999998 rather than exactly 11.0.
    // The explicit cast to (int) performs strict truncation. Without this epsilon,
    // 10.999999999999998 would incorrectly truncate back down to 10, failing to cross
    // the integer boundary despite a mathematically complete window.
    return (int) (state.get().currentLimit() + 1e-9);
  }

  /**
   * Updates the algorithm state based on the outcome of a completed call.
   *
   * <p>This method is designed to be called from the hot path of every completed request.
   * It uses a lock-free CAS retry loop (identical to {@link VegasLimitAlgorithm#update})
   * to guarantee thread safety without blocking:
   * <ol>
   *   <li>Read the current immutable state snapshot.</li>
   *   <li>Compute the next state from it (pure arithmetic, no side effects).</li>
   *   <li>Attempt an atomic compare-and-set. If another thread modified the state between
   *       steps 1 and 3, the CAS fails and the loop retries with the latest state.</li>
   * </ol>
   *
   * <p>The retry loop is wait-free in practice: the compute step involves only a few
   * floating-point operations, and contention is bounded by the number of threads
   * concurrently completing calls through this bulkhead.
   *
   * <h3>FIX #10: Zero/Negative RTT Guard</h3>
   * <p>RTT values of zero or below are silently ignored. This is consistent with
   * {@link VegasLimitAlgorithm} behavior. A zero RTT typically indicates a measurement
   * error (e.g., the call was a no-op, or the nano-time source has insufficient
   * resolution). Treating it as a valid success would incorrectly inflate the limit;
   * treating it as a valid failure would incorrectly deflate it.
   *
   * @param rtt       the round-trip time measured by the paradigm strategy. Values
   *                  {@code <= 0} are silently ignored.
   * @param isSuccess {@code true} if the call completed without a business or technical
   *                  error, {@code false} if it failed (timeout, exception, 5xx, etc.)
   */
  @Override
  public void update(Duration rtt, boolean isSuccess) {

    // Guard: ignore degenerate RTT values that indicate measurement errors.
    // Without this, a Duration.ZERO from a no-op call would be treated as a normal
    // sample and influence the limit in an undefined direction.
    if (rtt.toNanos() <= 0) {
      return;
    }

    // Lock-free CAS retry loop. Each iteration:
    // 1. Reads the current immutable state snapshot
    // 2. Computes the next state (pure function of current state + input)
    // 3. Atomically swaps if no other thread modified the state in between
    AimdState current;
    AimdState next;
    do {
      current = state.get();

      // ── Step 1: Update the EWMA-smoothed error rate ──
      //
      // The error rate is a sliding average that tracks the recent proportion of
      // failures. Each call contributes a binary sample:
      //   - Success → 0.0 (pulls the rate down toward "all healthy")
      //   - Failure → 1.0 (pulls the rate up toward "all failing")
      //
      // The EWMA formula:
      //   newRate = oldRate × (1 - α) + sample × α
      //
      // where α is the smoothingFactor. The effective "memory" of the EWMA is
      // approximately 1/α samples. At α=0.1, the last ~10 samples dominate.
      //
      // Special case: when smoothingFactor=1.0 (classic mode), the formula reduces to:
      //   newRate = 0.0 × 0.0 + sample × 1.0 = sample
      // This means every sample fully overwrites the rate — no memory, no smoothing.
      // After a success, errorRate=0.0; after a failure, errorRate=1.0. Combined with
      // errorRateThreshold=0.0, this reproduces the original per-call decrease behavior.
      double sample = isSuccess ? 0.0 : 1.0;
      double newErrorRate = current.smoothedErrorRate() * (1.0 - smoothingFactor)
          + sample * smoothingFactor;

      // ── Step 2: Calculate the new concurrency limit ──
      double newLimit;

      if (isSuccess) {
        // ── Additive Increase Phase ──
        //
        // The call succeeded — the downstream system handled it. We cautiously
        // increase the limit to probe whether more capacity is available.

        if (windowedIncrease) {
          // Windowed (TCP-style) increase: +1/currentLimit per success.
          //
          // The key insight from TCP congestion avoidance (RFC 5681 §3.1) is that
          // the probing increment should be inversely proportional to the current
          // window size. This ensures the limit grows by exactly +1 per "window"
          // of successful calls, regardless of the transaction rate:
          //
          //   At limit=10:  each success adds 0.1   → 10 successes  = +1
          //   At limit=100: each success adds 0.01  → 100 successes = +1
          //   At limit=1:   each success adds 1.0   → 1 success     = +1
          //
          // Without this, at 1000 RPS with fixed +1, the limit would increase by
          // ~1000/sec and hit maxLimit in milliseconds, only to crash down on the
          // first failure — creating violent high-frequency oscillations.
          newLimit = current.currentLimit() + 1.0 / current.currentLimit();
        } else {
          // Fixed (classic) increase: +1 per success.
          //
          // Simple and deterministic. The limit increases by exactly 1 for every
          // successful call. Growth rate equals transaction rate, which is acceptable
          // for low-to-moderate throughput systems but causes runaway inflation at
          // high RPS.
          newLimit = current.currentLimit() + 1.0;
        }

      } else if (newErrorRate > errorRateThreshold) {
        // ── Multiplicative Decrease Phase ──
        //
        // The call failed AND the smoothed error rate has crossed the threshold,
        // indicating sustained congestion rather than a transient hiccup. We
        // aggressively reduce the limit to shed load and give the downstream
        // service breathing room.
        //
        // The multiplication ensures the reduction is proportional to the current
        // limit: at limit=100 with backoffRatio=0.5, we drop to 50. At limit=10,
        // we drop to 5. This proportional behavior means the recovery time
        // (climbing back via additive increase) is also proportional.
        //
        // Classic mode note: with errorRateThreshold=0.0 and smoothingFactor=1.0,
        // a single failure sets errorRate=1.0, which is > 0.0 — so every failure
        // immediately triggers this branch. This exactly matches the original
        // pre-refactor behavior.
        newLimit = current.currentLimit() * backoffRatio;

      } else {
        // ── Transient Failure Absorption ──
        //
        // The call failed, but the smoothed error rate is still at or below the
        // threshold. This branch is only reachable when errorRateThreshold > 0.0
        // (stabilized mode). The failure has been recorded in the EWMA (it will
        // push the rate upward), but the accumulated evidence is not yet strong
        // enough to warrant a capacity reduction.
        //
        // This is the key difference from classic AIMD: isolated failures are
        // absorbed without any limit change. The limit holds steady while the
        // EWMA accumulates evidence. If failures continue, the rate will
        // eventually cross the threshold and trigger the decrease above.
        //
        // This branch is never reached in classic mode (errorRateThreshold=0.0)
        // because any non-zero error rate after a failure is > 0.0.
        newLimit = current.currentLimit();
      }

      // ── Step 3: Clamp the new limit to the configured bounds ──
      //
      // Ensure the limit never drops below minLimit (system must always be able
      // to send probe requests) or exceeds maxLimit (prevent unbounded resource
      // consumption). The clamping is applied after the arithmetic to keep the
      // calculation logic clean and the boundary enforcement in one place.
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      // Bundle the new limit and error rate into an immutable snapshot for the
      // atomic CAS swap.
      next = new AimdState(newLimit, newErrorRate);

      // Attempt the atomic swap. If another thread updated the state between our
      // read (line: current = state.get()) and this CAS, the swap fails and we
      // retry the entire computation with the latest state. This guarantees that
      // every state transition is based on a consistent snapshot, even under
      // high concurrency.
    } while (!state.compareAndSet(current, next));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Internal State Record
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of the algorithm's mutable state.
   *
   * <p>Bundling both fields into a single record is the key to thread safety in this
   * implementation. The {@link AtomicReference} holding this record allows exactly one
   * atomic operation — {@code compareAndSet} — to read and replace the entire state.
   * This prevents the "torn read" problem that would occur with two separate atomic
   * variables: thread A reads {@code currentLimit} from state X, then thread B updates
   * both fields to state Y, then thread A reads {@code smoothedErrorRate} from state Y —
   * mixing old limit with new error rate and producing an inconsistent computation.
   *
   * <p>The {@code currentLimit} is stored as {@code double} rather than {@code int} to
   * support the fractional increments produced by windowed increase ({@code +1/currentLimit}).
   * Without this, the increments would be truncated to 0 for any limit > 1, making the
   * windowed strategy non-functional. The conversion to {@code int} happens only in
   * {@link #getLimit()}, where truncation is the desired behavior (the effective limit
   * increases only when enough fractional increments accumulate to cross an integer boundary).
   *
   * @param currentLimit      The current concurrency limit as a double to support fractional
   *                          windowed increments. Always in [{@code minLimit}, {@code maxLimit}].
   * @param smoothedErrorRate The EWMA-smoothed error rate, ranging from 0.0 (all recent calls
   *                          succeeded) to 1.0 (all recent calls failed). Used to determine
   *                          whether the multiplicative decrease should be triggered.
   */
  private record AimdState(double currentLimit, double smoothedErrorRate) {
  }
}
