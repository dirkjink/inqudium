package eu.inqudium.reactor.retry;

import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.RetryCore;
import eu.inqudium.core.retry.RetryDecision;
import eu.inqudium.core.retry.RetryEvent;
import eu.inqudium.core.retry.RetryException;
import eu.inqudium.core.retry.RetrySnapshot;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Non-blocking, reactive retry implementation for use with
 * Project Reactor (Spring WebFlux).
 *
 * <p>Uses recursive {@link Mono#defer} with {@link Mono#delay} for
 * non-blocking backoff, keeping the event loop free during wait periods.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link RetryCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = RetryConfig.builder("downstream-call")
 *     .maxAttempts(4)
 *     .exponentialBackoff(Duration.ofMillis(200))
 *     .retryOnExceptions(IOException.class)
 *     .build();
 *
 * var retry = new ReactiveRetry(config);
 *
 * // Protect a Mono
 * Mono<String> result = retry.execute(
 *     () -> webClient.get().uri("/api").retrieve().bodyToMono(String.class));
 *
 * // With fallback
 * Mono<String> result = retry.executeWithFallback(
 *     () -> webClient.get().uri("/api").retrieve().bodyToMono(String.class),
 *     error -> Mono.just("fallback"));
 *
 * // Operator style
 * Mono<String> result = webClient.get().uri("/api")
 *     .retrieve()
 *     .bodyToMono(String.class)
 *     .transformDeferred(retry.monoOperator());
 *
 * // Listen to events
 * retry.events().subscribe(event -> log.info("Retry: {}", event));
 * }</pre>
 */
public class ReactiveRetry {

  private final RetryConfig config;
  private final Clock clock;
  private final Sinks.Many<RetryEvent> eventSink;

  public ReactiveRetry(RetryConfig config) {
    this(config, Clock.systemUTC());
  }

  public ReactiveRetry(RetryConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
  }

  // ======================== Mono execution ========================

  /**
   * Protects a lazily created {@link Mono} with retry logic.
   *
   * <p>The supplier is called on every attempt, creating a fresh Mono
   * for each retry. Backoff delays are non-blocking via {@link Mono#delay}.
   *
   * @param monoSupplier supplier that creates the mono for each attempt
   * @param <T>          the element type
   * @return a mono with retry logic applied
   */
  public <T> Mono<T> execute(Supplier<Mono<T>> monoSupplier) {
    return Mono.defer(() -> {
      Instant now = clock.instant();
      RetrySnapshot initial = RetryCore.startFirstAttempt(now);
      emitEvent(RetryEvent.attemptStarted(
          config.name(), 1, initial.totalElapsed(now), now));
      return attemptMono(monoSupplier, initial);
    });
  }

  /**
   * Protects a {@link Mono} with retry logic.
   *
   * <p>Note: the same Mono instance is resubscribed on retry.
   * For cold Monos this works correctly; for hot Monos, use the
   * supplier variant instead.
   */
  public <T> Mono<T> execute(Mono<T> mono) {
    return execute(() -> mono);
  }

  /**
   * Protects a {@link Mono} with a reactive fallback on exhaustion.
   */
  public <T> Mono<T> executeWithFallback(
      Supplier<Mono<T>> monoSupplier,
      Function<Throwable, Mono<T>> fallbackFunction) {

    return execute(monoSupplier)
        .onErrorResume(RetryException.class, fallbackFunction);
  }

  // ======================== Flux execution ========================

  /**
   * Protects a lazily created {@link Flux} with retry logic.
   *
   * <p>Retries the entire flux subscription on failure.
   */
  public <T> Flux<T> executeMany(Supplier<Flux<T>> fluxSupplier) {
    return Flux.defer(() -> {
      Instant now = clock.instant();
      RetrySnapshot initial = RetryCore.startFirstAttempt(now);
      emitEvent(RetryEvent.attemptStarted(
          config.name(), 1, initial.totalElapsed(now), now));
      return attemptFlux(fluxSupplier, initial);
    });
  }

  /**
   * Protects a {@link Flux} with retry logic.
   */
  public <T> Flux<T> executeMany(Flux<T> flux) {
    return executeMany(() -> flux);
  }

  /**
   * Protects a {@link Flux} with a reactive fallback on exhaustion.
   */
  public <T> Flux<T> executeManyWithFallback(
      Supplier<Flux<T>> fluxSupplier,
      Function<Throwable, Publisher<T>> fallbackFunction) {

    return executeMany(fluxSupplier)
        .onErrorResume(RetryException.class, fallbackFunction);
  }

  // ======================== Operator style ========================

  /**
   * Returns a function for use with {@link Mono#transformDeferred}.
   *
   * <pre>{@code
   * webClient.get().uri("/api")
   *     .retrieve()
   *     .bodyToMono(String.class)
   *     .transformDeferred(retry.monoOperator());
   * }</pre>
   */
  public <T> Function<Mono<T>, Mono<T>> monoOperator() {
    return mono -> execute(() -> mono);
  }

  /**
   * Returns a function for use with {@link Flux#transformDeferred}.
   */
  public <T> Function<Flux<T>, Flux<T>> fluxOperator() {
    return flux -> executeMany(() -> flux);
  }

  // ======================== Internal — Mono retry loop ========================

  private <T> Mono<T> attemptMono(Supplier<Mono<T>> monoSupplier, RetrySnapshot snapshot) {
    return monoSupplier.get()
        .flatMap(result -> {
          // Check for result-based retry
          RetryDecision resultDecision = RetryCore.evaluateResult(snapshot, config, result);
          if (resultDecision == null) {
            // Success
            RetrySnapshot completed = RetryCore.recordSuccess(snapshot);
            Instant now = clock.instant();
            emitEvent(RetryEvent.attemptSucceeded(
                config.name(), completed.attemptNumber(),
                completed.totalElapsed(now), now));
            return Mono.just(result);
          }
          // Result-based retry
          return handleMonoDecision(resultDecision, monoSupplier, snapshot, null);
        })
        .onErrorResume(RetryException.class, Mono::error)
        .onErrorResume(error -> {
          RetryDecision decision = RetryCore.evaluateFailure(snapshot, config, error);
          return handleMonoDecision(decision, monoSupplier, snapshot, error);
        });
  }

  @SuppressWarnings("unchecked")
  private <T> Mono<T> handleMonoDecision(
      RetryDecision decision,
      Supplier<Mono<T>> monoSupplier,
      RetrySnapshot snapshot,
      Throwable error) {

    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    return switch (decision) {
      case RetryDecision.DoRetry doRetry -> {
        emitEvent(error != null
            ? RetryEvent.retryScheduled(
            config.name(), snapshot.attemptNumber(), doRetry.delay(),
            elapsed, error, now)
            : RetryEvent.resultRetryScheduled(
            config.name(), snapshot.attemptNumber(), doRetry.delay(),
            elapsed, now));

        RetrySnapshot waitingSnapshot = doRetry.snapshot();

        // Non-blocking delay, then start next attempt
        Mono<Void> delayMono = doRetry.delay().isZero()
            ? Mono.empty()
            : Mono.delay(doRetry.delay()).then();

        yield delayMono.then(Mono.defer(() -> {
          Instant retryNow = clock.instant();
          RetrySnapshot nextSnapshot = RetryCore.startNextAttempt(waitingSnapshot, retryNow);
          emitEvent(RetryEvent.attemptStarted(
              config.name(), nextSnapshot.attemptNumber(),
              nextSnapshot.totalElapsed(retryNow), retryNow));
          return attemptMono(monoSupplier, nextSnapshot);
        }));
      }
      case RetryDecision.DoNotRetry doNotRetry -> {
        emitEvent(RetryEvent.failedNonRetryable(
            config.name(), snapshot.attemptNumber(), elapsed,
            doNotRetry.failure(), now));
        yield Mono.error(doNotRetry.failure());
      }
      case RetryDecision.RetriesExhausted exhausted -> {
        emitEvent(RetryEvent.retriesExhausted(
            config.name(), snapshot.attemptNumber(), elapsed,
            exhausted.failure(), now));
        yield Mono.error(new RetryException(
            config.name(), exhausted.snapshot().totalAttempts(),
            exhausted.failure(), exhausted.snapshot().failures()));
      }
    };
  }

  // ======================== Internal — Flux retry loop ========================

  private <T> Flux<T> attemptFlux(Supplier<Flux<T>> fluxSupplier, RetrySnapshot snapshot) {
    // Use an AtomicReference to track success per subscription
    AtomicReference<RetrySnapshot> snapshotRef = new AtomicReference<>(snapshot);

    return fluxSupplier.get()
        .doOnComplete(() -> {
          RetrySnapshot current = snapshotRef.get();
          RetrySnapshot completed = RetryCore.recordSuccess(current);
          snapshotRef.set(completed);
          Instant now = clock.instant();
          emitEvent(RetryEvent.attemptSucceeded(
              config.name(), completed.attemptNumber(),
              completed.totalElapsed(now), now));
        })
        .onErrorResume(RetryException.class, Flux::error)
        .onErrorResume(error -> {
          RetrySnapshot current = snapshotRef.get();
          RetryDecision decision = RetryCore.evaluateFailure(current, config, error);
          return handleFluxDecision(decision, fluxSupplier, current, error);
        });
  }

  @SuppressWarnings("unchecked")
  private <T> Flux<T> handleFluxDecision(
      RetryDecision decision,
      Supplier<Flux<T>> fluxSupplier,
      RetrySnapshot snapshot,
      Throwable error) {

    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    return switch (decision) {
      case RetryDecision.DoRetry doRetry -> {
        emitEvent(RetryEvent.retryScheduled(
            config.name(), snapshot.attemptNumber(), doRetry.delay(),
            elapsed, error, now));

        RetrySnapshot waitingSnapshot = doRetry.snapshot();

        Mono<Void> delayMono = doRetry.delay().isZero()
            ? Mono.empty()
            : Mono.delay(doRetry.delay()).then();

        yield delayMono.thenMany(Flux.defer(() -> {
          Instant retryNow = clock.instant();
          RetrySnapshot nextSnapshot = RetryCore.startNextAttempt(waitingSnapshot, retryNow);
          emitEvent(RetryEvent.attemptStarted(
              config.name(), nextSnapshot.attemptNumber(),
              nextSnapshot.totalElapsed(retryNow), retryNow));
          return attemptFlux(fluxSupplier, nextSnapshot);
        }));
      }
      case RetryDecision.DoNotRetry doNotRetry -> {
        emitEvent(RetryEvent.failedNonRetryable(
            config.name(), snapshot.attemptNumber(), elapsed,
            doNotRetry.failure(), now));
        yield Flux.error(doNotRetry.failure());
      }
      case RetryDecision.RetriesExhausted exhausted -> {
        emitEvent(RetryEvent.retriesExhausted(
            config.name(), snapshot.attemptNumber(), elapsed,
            exhausted.failure(), now));
        yield Flux.error(new RetryException(
            config.name(), exhausted.snapshot().totalAttempts(),
            exhausted.failure(), exhausted.snapshot().failures()));
      }
    };
  }

  // ======================== Events ========================

  /**
   * Returns a hot {@link Flux} of retry events.
   */
  public Flux<RetryEvent> events() {
    return eventSink.asFlux();
  }

  private void emitEvent(RetryEvent event) {
    eventSink.tryEmitNext(event);
  }

  // ======================== Introspection ========================

  /**
   * Returns the configuration.
   */
  public RetryConfig getConfig() {
    return config;
  }
}
