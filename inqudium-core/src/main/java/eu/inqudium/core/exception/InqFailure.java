package eu.inqudium.core.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Cause-chain navigation utility for identifying Inqudium interventions
 * buried inside framework-wrapped exceptions.
 *
 * <p>In practice, exceptions are routinely wrapped by frameworks, proxies,
 * and reflection machinery:
 * <pre>
 * Origin:          InqCallNotPermittedException
 * ↓ Future.get()   ExecutionException(cause: InqCallNotPermittedException)
 * ↓ Spring AOP     UndeclaredThrowableException(cause: ...)
 * ↓ JDK Proxy      InvocationTargetException(cause: ...)
 * </pre>
 *
 * <p>{@code InqFailure.find()} traverses the entire cause chain — handling
 * circular references — and returns the first {@link InqException} found.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * catch (RuntimeException e) {
 *     InqFailure.find(e)
 *         .ifCircuitBreakerOpen(info -> log.warn("Breaker open: {}", info.getElementName()))
 *         .ifRetryExhausted(info -> log.error("Retries exhausted after {} attempts", info.getAttempts()))
 *         .orElseThrow(); // re-throw if not an Inqudium intervention
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public final class InqFailure {

  private final Throwable original;
  private final InqException found;
  private boolean handled;

  private InqFailure(Throwable original, InqException found) {
    this.original = original;
    this.found = found;
    this.handled = false;
  }

  /**
   * Searches the cause chain of the given throwable for the first {@link InqException}.
   *
   * <p>Traverses {@link Throwable#getCause()} recursively, handles circular cause
   * references via identity tracking, and returns a result that can be inspected
   * via the fluent API.
   *
   * @param throwable the exception to inspect (may be null)
   * @return a result object for fluent inspection
   */
  public static InqFailure find(Throwable throwable) {
    if (throwable == null) {
      return new InqFailure(null, null);
    }

    // Track visited throwables to handle circular cause chains
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = throwable;

    while (current != null && visited.add(current)) {
      if (current instanceof InqException inqException) {
        return new InqFailure(throwable, inqException);
      }
      current = current.getCause();
    }

    return new InqFailure(throwable, null);
  }

  /**
   * Returns {@code true} if an {@link InqException} was found in the cause chain.
   *
   * @return true if an Inqudium intervention was found
   */
  public boolean isPresent() {
    return found != null;
  }

  /**
   * Returns the found {@link InqException}, if any.
   *
   * @return the exception, or empty if no Inqudium intervention was found
   */
  public Optional<InqException> get() {
    return Optional.ofNullable(found);
  }

  /**
   * Invokes the consumer if the found exception is a {@link InqCallNotPermittedException}.
   *
   * @param consumer the handler for circuit breaker open events
   * @return this instance for chaining
   */
  public InqFailure ifCircuitBreakerOpen(Consumer<InqCallNotPermittedException> consumer) {
    if (found instanceof InqCallNotPermittedException ex) {
      consumer.accept(ex);
      handled = true;
    }
    return this;
  }

  /**
   * Invokes the consumer if the found exception is a {@link InqRequestNotPermittedException}.
   *
   * @param consumer the handler for rate limiter denied events
   * @return this instance for chaining
   */
  public InqFailure ifRateLimited(Consumer<InqRequestNotPermittedException> consumer) {
    if (found instanceof InqRequestNotPermittedException ex) {
      consumer.accept(ex);
      handled = true;
    }
    return this;
  }

  /**
   * Invokes the consumer if the found exception is a {@link InqBulkheadFullException}.
   *
   * @param consumer the handler for bulkhead full events
   * @return this instance for chaining
   */
  public InqFailure ifBulkheadFull(Consumer<InqBulkheadFullException> consumer) {
    if (found instanceof InqBulkheadFullException ex) {
      consumer.accept(ex);
      handled = true;
    }
    return this;
  }

  /**
   * Invokes the consumer if the found exception is a {@link InqTimeLimitExceededException}.
   *
   * @param consumer the handler for time limit exceeded events
   * @return this instance for chaining
   */
  public InqFailure ifTimeLimitExceeded(Consumer<InqTimeLimitExceededException> consumer) {
    if (found instanceof InqTimeLimitExceededException ex) {
      consumer.accept(ex);
      handled = true;
    }
    return this;
  }

  /**
   * Invokes the consumer if the found exception is a {@link InqRetryExhaustedException}.
   *
   * @param consumer the handler for retry exhausted events
   * @return this instance for chaining
   */
  public InqFailure ifRetryExhausted(Consumer<InqRetryExhaustedException> consumer) {
    if (found instanceof InqRetryExhaustedException ex) {
      consumer.accept(ex);
      handled = true;
    }
    return this;
  }

  /**
   * Re-throws the original exception if no Inqudium intervention was found
   * or if none of the {@code if*} handlers matched.
   *
   * @throws RuntimeException the original exception if unhandled
   */
  public void orElseThrow() {
    if (!handled && original != null) {
      if (original instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(original);
    }
  }

  /**
   * Re-throws the original exception if no Inqudium intervention was found.
   * Unlike {@link #orElseThrow()}, this re-throws even if a handler matched.
   *
   * @throws RuntimeException the original exception if no InqException was found
   */
  public void orElseThrowIfAbsent() {
    if (found == null && original != null) {
      if (original instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(original);
    }
  }
}
