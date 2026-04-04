package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Package-private ID generator for standalone executions (not part of a wrapper chain).
 * Uses static counters — one CAS per call, zero object allocation.
 */
public final class StandaloneIdGenerator {

  private static final AtomicLong CHAIN_ID = new AtomicLong();
  private static final AtomicLong CALL_ID = new AtomicLong();

  public static long nextChainId() {
    return CHAIN_ID.incrementAndGet();
  }

  public static long nextCallId() {
    return CALL_ID.incrementAndGet();
  }

  private StandaloneIdGenerator() {
  }
}
