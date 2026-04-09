package eu.inqudium.core.element.retry.dsl;

import java.time.Duration;

class DefaultRetryProtection implements RetryProtection {

  private int maxAttempts = 3; // Fallback: 3 Versuche (inklusive dem ersten)
  private Duration baseWaitDuration = Duration.ofMillis(500); // Fallback
  private double backoffMultiplier = 1.0; // Fallback: Lineares Warten (kein Backoff)

  @Override
  public RetryProtection attemptingUpTo(int maxAttempts) {
    this.maxAttempts = maxAttempts;
    return this;
  }

  @Override
  public RetryProtection waitingBetweenAttempts(Duration waitDuration) {
    this.baseWaitDuration = waitDuration;
    return this;
  }

  @Override
  public RetryProtection backingOffExponentially(double multiplier) {
    this.backoffMultiplier = multiplier;
    return this;
  }

  @Override
  public RetryConfig applyStrictProfile() {
    // Strict: Sehr schnelles Fail-Fast, nur 2 Versuche, extrem kurze Pause, kein Backoff
    return new RetryConfig(2, Duration.ofMillis(100), 1.0);
  }

  @Override
  public RetryConfig applyBalancedProfile() {
    // Balanced: Der Industriestandard. 3 Versuche, sanftes exponentielles Warten
    return new RetryConfig(3, Duration.ofMillis(500), 1.5);
  }

  @Override
  public RetryConfig applyPermissiveProfile() {
    // Permissive: Sehr hartnäckig. Z.B. für unkritische Background-Jobs, die unbedingt durchlaufen müssen
    return new RetryConfig(10, Duration.ofSeconds(1), 2.0);
  }

  @Override
  public RetryConfig apply() {
    return new RetryConfig(maxAttempts, baseWaitDuration, backoffMultiplier);
  }
}
