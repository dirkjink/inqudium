package eu.inqudium.core.exception;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;

import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
  boolean enableExceptionOptimization = false;

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
   * Strips common wrapper exceptions to recover the original cause.
   *
   * <p>Many frameworks and JDK APIs wrap exceptions in generic containers during
   * proxying, reflection, or asynchronous execution. This method peels through
   * these layers to return the first non-wrapper exception:
   * <pre>
   * UncheckedIOException         → getCause()
   * ExecutionException           → getCause()
   * CompletionException          → getCause()
   * InvocationTargetException    → getCause()
   * UndeclaredThrowableException → getUndeclaredThrowable()
   * ExceptionInInitializerError  → getException()
   * </pre>
   *
   * <p>Unwrapping stops as soon as the current throwable is not one of the
   * recognized wrapper types, or when a circular cause chain is detected.
   * If the input is {@code null} or has no cause, it is returned as-is.
   *
   * <h2>Usage</h2>
   * <pre>{@code
   * // Inside an exception constructor — unwrap before storing the cause
   * super(callId, code, name, type, message, InqFailure.unwrap(cause));
   *
   * // Standalone — recover the original exception
   * Throwable original = InqFailure.unwrap(executionException);
   * }</pre>
   *
   * @param throwable the exception to unwrap (may be null)
   * @return the innermost non-wrapper throwable, or the input if it is not a wrapper
   */
  public static Throwable unwrap(Throwable throwable) {
    if (throwable == null) {
      return null;
    }

    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = throwable;

    while (visited.add(current)) {
      Throwable cause = unwrapOnce(current);
      if (cause == null || cause == current) {
        return current;
      }
      current = cause;
    }

    // Circular cause chain — return the last non-circular entry
    return current;
  }

  private static Throwable unwrapOnce(Throwable throwable) {
    if (throwable instanceof UncheckedIOException e) return e.getCause();
    if (throwable instanceof ExecutionException e) return e.getCause();
    if (throwable instanceof CompletionException e) return e.getCause();
    if (throwable instanceof InvocationTargetException e) return e.getCause();
    if (throwable instanceof UndeclaredThrowableException e) return e.getUndeclaredThrowable();
    if (throwable instanceof ExceptionInInitializerError e) return e.getException();
    return null;  // not a wrapper — stop unwrapping
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
   * Invokes the consumer if the found exception is a {@link InqBulkheadInterruptedException}.
   *
   * @param consumer the handler for bulkhead interrupt events
   * @return this instance for chaining
   */
  public InqFailure ifBulkheadInterrupted(Consumer<InqBulkheadInterruptedException> consumer) {
    if (found instanceof InqBulkheadInterruptedException ex) {
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
      throw new InqRuntimeException(original, enableExceptionOptimization);
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
      throw new InqRuntimeException(original, enableExceptionOptimization);
    }
  }
}
