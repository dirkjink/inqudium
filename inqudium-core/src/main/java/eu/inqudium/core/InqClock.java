package eu.inqudium.core;

import java.time.Instant;

/**
 * Functional interface for injectable time.
 *
 * <p>Every time-dependent algorithm in Inqudium core uses {@code InqClock}
 * instead of {@link Instant#now()}. This ensures deterministic testability:
 * tests control time explicitly without {@code Thread.sleep()} or flakiness (ADR-016).
 *
 * <h2>Production usage</h2>
 * <pre>{@code
 * var config = CircuitBreakerConfig.builder()
 *     .clock(InqClock.system())   // default — can be omitted
 *     .build();
 * }</pre>
 *
 * <h2>Test usage</h2>
 * <pre>{@code
 * var fixedTime = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
 * InqClock testClock = fixedTime::get;
 *
 * // Advance time by 5 seconds — no waiting
 * fixedTime.set(fixedTime.get().plusSeconds(5));
 * }</pre>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqClock {

    /**
     * Returns the current instant.
     *
     * @return the current time as seen by this clock
     */
    Instant instant();

    /**
     * Returns a clock backed by {@link Instant#now()}.
     *
     * @return the system clock
     */
    static InqClock system() {
        return Instant::now;
    }
}
