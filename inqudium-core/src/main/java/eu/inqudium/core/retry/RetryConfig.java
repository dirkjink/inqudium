package eu.inqudium.core.retry;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;
import eu.inqudium.core.retry.backoff.BackoffStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable configuration for the Retry element (ADR-018).
 *
 * @since 0.1.0
 */
public final class RetryConfig implements InqConfig {

  private static final RetryConfig DEFAULTS = RetryConfig.builder().build();

  private final int maxAttempts;
  private final Duration initialInterval;
  private final BackoffStrategy backoffStrategy;
  private final Duration maxInterval;
  private final Set<Class<? extends Throwable>> retryOn;
  private final Set<Class<? extends Throwable>> ignoreOn;
  private final boolean retryOnInqExceptions;
  private final Predicate<Throwable> retryOnPredicate;
  private final InqCompatibility compatibility;
  private final InqClock clock;
  private final Logger logger;
  private final InqCallIdGenerator callIdGenerator;

  private RetryConfig(Builder builder) {
    this.maxAttempts = builder.maxAttempts;
    this.initialInterval = builder.initialInterval;
    this.backoffStrategy = builder.backoffStrategy;
    this.maxInterval = builder.maxInterval;
    this.retryOn = Collections.unmodifiableSet(new HashSet<>(builder.retryOn));
    this.ignoreOn = Collections.unmodifiableSet(new HashSet<>(builder.ignoreOn));
    this.retryOnInqExceptions = builder.retryOnInqExceptions;
    this.retryOnPredicate = builder.retryOnPredicate;
    this.compatibility = builder.compatibility;
    this.clock = builder.clock;
    this.logger = builder.logger;
    this.callIdGenerator = builder.callIdGenerator;
  }

  public static RetryConfig ofDefaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Duration getInitialInterval() {
    return initialInterval;
  }

  public BackoffStrategy getBackoffStrategy() {
    return backoffStrategy;
  }

  public Duration getMaxInterval() {
    return maxInterval;
  }

  public Set<Class<? extends Throwable>> getRetryOn() {
    return retryOn;
  }

  public Set<Class<? extends Throwable>> getIgnoreOn() {
    return ignoreOn;
  }

  public boolean isRetryOnInqExceptions() {
    return retryOnInqExceptions;
  }

  public Predicate<Throwable> getRetryOnPredicate() {
    return retryOnPredicate;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }

  @Override
  public InqClock getClock() {
    return clock;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public InqCallIdGenerator getCallIdGenerator() {
    return callIdGenerator;
  }

  public static final class Builder {
    private final Set<Class<? extends Throwable>> retryOn = new HashSet<>();
    private final Set<Class<? extends Throwable>> ignoreOn = new HashSet<>();
    private int maxAttempts = 3;
    private Duration initialInterval = Duration.ofMillis(500);
    private BackoffStrategy backoffStrategy = BackoffStrategy.exponentialWithEqualJitter();
    private Duration maxInterval = Duration.ofSeconds(30);
    private boolean retryOnInqExceptions = false;
    private Predicate<Throwable> retryOnPredicate = null;
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();
    private InqClock clock = InqClock.system();
    private Logger logger = LoggerFactory.getLogger(RetryConfig.class);
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

    private Builder() {
    }

    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder initialInterval(Duration initialInterval) {
      this.initialInterval = Objects.requireNonNull(initialInterval);
      return this;
    }

    public Builder backoffStrategy(BackoffStrategy strategy) {
      this.backoffStrategy = Objects.requireNonNull(strategy);
      return this;
    }

    public Builder maxInterval(Duration maxInterval) {
      this.maxInterval = Objects.requireNonNull(maxInterval);
      return this;
    }

    public Builder retryOn(Class<? extends Throwable> exceptionType) {
      this.retryOn.add(exceptionType);
      return this;
    }

    public Builder ignoreOn(Class<? extends Throwable> exceptionType) {
      this.ignoreOn.add(exceptionType);
      return this;
    }

    public Builder retryOnInqExceptions(boolean retry) {
      this.retryOnInqExceptions = retry;
      return this;
    }

    public Builder retryOnPredicate(Predicate<Throwable> predicate) {
      this.retryOnPredicate = predicate;
      return this;
    }

    public Builder compatibility(InqCompatibility compatibility) {
      this.compatibility = Objects.requireNonNull(compatibility);
      return this;
    }

    public Builder clock(InqClock clock) {
      this.clock = Objects.requireNonNull(clock);
      return this;
    }

    public Builder logger(Logger logger) {
      this.logger = Objects.requireNonNull(logger);
      return this;
    }

    public Builder callIdGenerator(InqCallIdGenerator gen) {
      this.callIdGenerator = Objects.requireNonNull(gen);
      return this;
    }

    public RetryConfig build() {
      return new RetryConfig(this);
    }
  }
}
