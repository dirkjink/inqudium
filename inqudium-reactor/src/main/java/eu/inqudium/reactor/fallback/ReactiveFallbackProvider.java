package eu.inqudium.reactor.fallback;

import eu.inqudium.core.fallback.FallbackConfig;
import eu.inqudium.core.fallback.FallbackCore;
import eu.inqudium.core.fallback.FallbackEvent;
import eu.inqudium.core.fallback.FallbackException;
import eu.inqudium.core.fallback.FallbackSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Non-blocking, reactive fallback provider implementation for use with
 * Project Reactor (Spring WebFlux).
 *
 * <p>Intercepts error signals from the primary Mono/Flux and routes them
 * to registered fallback handlers. Also supports result-based fallback
 * for unacceptable values.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link FallbackCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = FallbackConfig.<String>builder("user-service")
 *     .onException(IOException.class, ex -> "cached-user")
 *     .onException(TimeoutException.class, ex -> "timeout-default")
 *     .onResult(result -> result == null, () -> "unknown-user")
 *     .onAnyException(ex -> "generic-fallback")
 *     .build();
 *
 * var fallback = new ReactiveFallbackProvider<>(config);
 *
 * // Protect a Mono
 * Mono<String> user = fallback.execute(
 *     webClient.get().uri("/users/42").retrieve().bodyToMono(String.class));
 *
 * // Operator style
 * Mono<String> user = webClient.get().uri("/users/42")
 *     .retrieve()
 *     .bodyToMono(String.class)
 *     .transformDeferred(fallback.monoOperator());
 *
 * // Events
 * fallback.events().subscribe(event -> log.info("Fallback: {}", event));
 * }</pre>
 */
public class ReactiveFallbackProvider<T> {

  private final FallbackConfig<T> config;
  private final Clock clock;
  private final Sinks.Many<FallbackEvent> eventSink;

  public ReactiveFallbackProvider(FallbackConfig<T> config) {
    this(config, Clock.systemUTC());
  }

  public ReactiveFallbackProvider(FallbackConfig<T> config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
  }

  // ======================== Mono execution ========================

  /**
   * Protects a {@link Mono} with fallback logic.
   *
   * <p>On error signal: routes to the first matching exception handler.
   * On unacceptable result: routes to the first matching result handler.
   */
  public Mono<T> execute(Mono<T> mono) {
    return Mono.defer(() -> {
      Instant now = clock.instant();
      FallbackSnapshot snapshot = FallbackCore.start(now);
      emitEvent(FallbackEvent.primaryStarted(config.name(), now));

      return mono
          .flatMap(result -> handleResult(snapshot, result))
          .switchIfEmpty(Mono.defer(() -> handleEmptyResult(snapshot)))
          .onErrorResume(error -> handleError(snapshot, error));
    });
  }

  /**
   * Protects a lazily created {@link Mono} with fallback logic.
   */
  public Mono<T> execute(Supplier<Mono<T>> monoSupplier) {
    return Mono.defer(() -> execute(monoSupplier.get()));
  }

  // ======================== Flux execution ========================

  /**
   * Protects a {@link Flux} with fallback logic.
   *
   * <p>On error signal, the flux error is routed to the first matching
   * exception handler. The fallback value (if any) is emitted as a
   * single element followed by completion.
   */
  public Flux<T> executeMany(Flux<T> flux) {
    return Flux.defer(() -> {
      Instant now = clock.instant();
      FallbackSnapshot snapshot = FallbackCore.start(now);
      emitEvent(FallbackEvent.primaryStarted(config.name(), now));

      return flux
          .doOnComplete(() -> {
            Instant completedAt = clock.instant();
            FallbackCore.recordPrimarySuccess(snapshot, completedAt);
            emitEvent(FallbackEvent.primarySucceeded(
                config.name(), snapshot.elapsed(completedAt), completedAt));
          })
          .onErrorResume(error -> {
            Mono<T> recovered = handleError(snapshot, error);
            // Convert recovered Mono to single-element Flux
            return recovered.flux();
          });
    });
  }

  /**
   * Protects a lazily created {@link Flux} with fallback logic.
   */
  public Flux<T> executeMany(Supplier<Flux<T>> fluxSupplier) {
    return Flux.defer(() -> executeMany(fluxSupplier.get()));
  }

  // ======================== Operator style ========================

  /**
   * Returns a function for use with {@link Mono#transformDeferred}.
   *
   * <pre>{@code
   * webClient.get().uri("/api")
   *     .retrieve()
   *     .bodyToMono(String.class)
   *     .transformDeferred(fallback.monoOperator());
   * }</pre>
   */
  public Function<Mono<T>, Mono<T>> monoOperator() {
    return this::execute;
  }

  /**
   * Returns a function for use with {@link Flux#transformDeferred}.
   */
  public Function<Flux<T>, Flux<T>> fluxOperator() {
    return this::executeMany;
  }

  // ======================== Internal — Error handling ========================

  private Mono<T> handleError(FallbackSnapshot snapshot, Throwable error) {
    Instant failedTime = clock.instant();
    emitEvent(FallbackEvent.primaryFailed(
        config.name(), snapshot.elapsed(failedTime), error, failedTime));

    FallbackCore.HandlerResolution<T> resolution =
        FallbackCore.resolveHandler(snapshot, config, error, failedTime);

    if (!resolution.matched()) {
      emitEvent(FallbackEvent.noHandlerMatched(
          config.name(), snapshot.elapsed(failedTime), error, failedTime));
      return Mono.error(error);
    }

    FallbackSnapshot fallingBack = resolution.snapshot();
    emitEvent(FallbackEvent.fallbackInvoked(
        config.name(), resolution.handler().name(),
        fallingBack.elapsed(failedTime), failedTime));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(resolution.handler(), error);
      Instant recoveredTime = clock.instant();
      FallbackCore.recordFallbackSuccess(fallingBack, recoveredTime);
      emitEvent(FallbackEvent.fallbackRecovered(
          config.name(), resolution.handler().name(),
          fallingBack.elapsed(recoveredTime), recoveredTime));
      return Mono.justOrEmpty(fallbackValue);

    } catch (Exception fallbackEx) {
      Instant fbFailedTime = clock.instant();
      FallbackCore.recordFallbackFailure(fallingBack, fallbackEx, fbFailedTime);
      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resolution.handler().name(),
          fallingBack.elapsed(fbFailedTime), fallbackEx, fbFailedTime));
      return Mono.error(new FallbackException(
          config.name(), FallbackException.Reason.FALLBACK_FAILED,
          error, fallbackEx));
    }
  }

  // ======================== Internal — Result handling ========================

  private Mono<T> handleResult(FallbackSnapshot snapshot, T result) {
    Instant resultTime = clock.instant();

    FallbackCore.HandlerResolution<T> resultResolution =
        FallbackCore.resolveResultHandler(snapshot, config, result, resultTime);

    if (resultResolution == null) {
      // Result is acceptable
      FallbackCore.recordPrimarySuccess(snapshot, resultTime);
      emitEvent(FallbackEvent.primarySucceeded(
          config.name(), snapshot.elapsed(resultTime), resultTime));
      return Mono.justOrEmpty(result);
    }

    return invokeResultFallback(snapshot, resultResolution, result, resultTime);
  }

  /**
   * Handles the case where the upstream Mono completed empty.
   *
   * <p>In Reactor, a supplier returning {@code null} produces an empty Mono
   * rather than emitting a {@code null} value (Reactive Streams forbids null
   * signals). This method treats empty completion as a "null result" and
   * routes it through the result-handler chain. If no result handler matches,
   * the Mono stays empty and primary success is recorded.
   */
  private Mono<T> handleEmptyResult(FallbackSnapshot snapshot) {
    Instant resultTime = clock.instant();

    FallbackCore.HandlerResolution<T> resultResolution =
        FallbackCore.resolveResultHandler(snapshot, config, null, resultTime);

    if (resultResolution == null) {
      // No result handler for null — treat as normal empty completion
      FallbackCore.recordPrimarySuccess(snapshot, resultTime);
      emitEvent(FallbackEvent.primarySucceeded(
          config.name(), snapshot.elapsed(resultTime), resultTime));
      return Mono.empty();
    }

    return invokeResultFallback(snapshot, resultResolution, null, resultTime);
  }

  private Mono<T> invokeResultFallback(
      FallbackSnapshot snapshot,
      FallbackCore.HandlerResolution<T> resolution,
      T originalResult,
      Instant resultTime) {

    FallbackSnapshot fallingBack = resolution.snapshot();
    emitEvent(FallbackEvent.resultFallbackInvoked(
        config.name(), resolution.handler().name(),
        fallingBack.elapsed(resultTime), resultTime));

    try {
      T fallbackValue = FallbackCore.invokeResultHandler(resolution.handler());
      Instant recoveredTime = clock.instant();
      FallbackCore.recordFallbackSuccess(fallingBack, recoveredTime);
      emitEvent(FallbackEvent.resultFallbackRecovered(
          config.name(), resolution.handler().name(),
          fallingBack.elapsed(recoveredTime), recoveredTime));
      return Mono.justOrEmpty(fallbackValue);

    } catch (Exception fallbackEx) {
      Instant fbFailedTime = clock.instant();
      FallbackCore.recordFallbackFailure(fallingBack, fallbackEx, fbFailedTime);
      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resolution.handler().name(),
          fallingBack.elapsed(fbFailedTime), fallbackEx, fbFailedTime));
      return Mono.error(new FallbackException(
          config.name(), FallbackException.Reason.FALLBACK_FAILED,
          new RuntimeException("Unacceptable result: " + originalResult), fallbackEx));
    }
  }

  // ======================== Events ========================

  /**
   * Returns a hot {@link Flux} of fallback events.
   */
  public Flux<FallbackEvent> events() {
    return eventSink.asFlux();
  }

  private void emitEvent(FallbackEvent event) {
    eventSink.tryEmitNext(event);
  }

  // ======================== Introspection ========================

  /**
   * Returns the configuration.
   */
  public FallbackConfig<T> getConfig() {
    return config;
  }
}
