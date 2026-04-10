package eu.inqudium.core.element.bulkhead.dsl;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

import java.time.Duration;

public final class DefaultBulkheadProtection implements BulkheadNaming, BulkheadProtection {

  private final InqBulkheadConfigBuilder<?, ?> inqBuilder;
  private String name;
  private int maxConcurrentCalls = 25; // Fallback
  private Duration maxWaitDuration = Duration.ofSeconds(0); // Fallback: Fail fast

  public DefaultBulkheadProtection(InqBulkheadConfigBuilder<?, ?> inqBuilder) {
    this.inqBuilder = inqBuilder;
  }

  @Override
  public BulkheadProtection named(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Bulkhead name must not be blank");
    }
    this.name = name;
    return this;
  }

  @Override
  public BulkheadProtection limitingConcurrentCallsTo(int maxCalls) {
    this.maxConcurrentCalls = maxCalls;
    return this;
  }

  @Override
  public BulkheadProtection waitingAtMostFor(Duration maxWait) {
    this.maxWaitDuration = maxWait;
    return this;
  }

  @Override
  public BulkheadConfig applyStrictProfile() {
    // Strict: Very low concurrency, absolute fail-fast (no waiting)
    return new BulkheadConfig(10, Duration.ZERO, createInqConfig());
  }

  @Override
  public BulkheadConfig applyBalancedProfile() {
    // Balanced: Good concurrency, short queueing allowed
    return new BulkheadConfig(50, Duration.ofMillis(500), createInqConfig());
  }

  @Override
  public BulkheadConfig applyPermissiveProfile() {
    // Permissive: High concurrency, long waiting allowed
    return new BulkheadConfig(200, Duration.ofSeconds(5), createInqConfig());
  }

  @Override
  public BulkheadConfig apply() {
    return new BulkheadConfig(maxConcurrentCalls, maxWaitDuration, createInqConfig());
  }

  private InqConfig createInqConfig() {
    if (inqBuilder == null) return null;
    return InqConfig.configure()
        .general()
        .with(inqBuilder, b -> b
            .maxConcurrentCalls(maxConcurrentCalls)
            .maxWaitDuration(maxWaitDuration)
        )
        .build();
  }
}