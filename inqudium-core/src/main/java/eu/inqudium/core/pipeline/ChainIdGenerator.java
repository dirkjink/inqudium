package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global, JVM-wide counter for generating unique chain identifiers.
 *
 * <p>Every new wrapper chain (i.e. the innermost wrapper that does not
 * delegate to another wrapper) obtains its chain ID from this counter.
 * All outer layers wrapping the same delegate inherit the same ID,
 * providing a zero-allocation correlation key across the entire stack.</p>
 *
 * <p>The counter is an {@link AtomicLong}, so chain ID generation is
 * thread-safe and lock-free (single CAS operation). IDs are monotonically
 * increasing and never reused within the same JVM lifetime.</p>
 *
 * <p>This class is a stateless utility holder — it cannot be instantiated.</p>
 */
public final class ChainIdGenerator {
    /**
     * Global counter for chain IDs — unique per JVM, monotonically increasing.
     * Starts at 0; the first chain created gets ID 1.
     */
    public static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

    /**
     * Prevent instantiation — this is a utility class.
     */
    private ChainIdGenerator() {
    }
}
