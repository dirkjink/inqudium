package eu.inqudium.core.element.retry.dsl;

public final class Resilience {

  private Resilience() {}

  // --- Retry (NEU) ---
  public static RetryProtection recoverWithRetry() {
    return new DefaultRetryProtection();
  }
}