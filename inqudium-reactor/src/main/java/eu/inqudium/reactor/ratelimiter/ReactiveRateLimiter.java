package eu.inqudium.reactor.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterCore;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import eu.inqudium.core.ratelimiter.RateLimiterSnapshot;
import eu.inqudium.core.ratelimiter.ReservationResult;
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
 * Non-blocking, reactive rate limiter implementation for use with
 * Project Reactor (Spring WebFlux).
 *
 * <p>Uses the same lock-free CAS approach as the imperative variant,
 * but wraps operations in {@link Mono} and {@link Flux} to stay fully
 * non-blocking. When a permit is not immediately available, the reactive
 * wrapper uses {@link Mono#delay} instead of thread-parking, keeping
 * the event loop free.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link RateLimiterCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = RateLimiterConfig.builder("api-limiter")
 *     .limitForPeriod(100, Duration.ofSeconds(1))
 *     .defaultTimeout(Duration.ofMillis(500))
 *     .build();
 *
 * var limiter = new ReactiveRateLimiter(config);
 *
 * // Protect a Mono (fail-fast)
 * Mono<String> result = limiter.execute(webClient.get()
 *     .uri("/api/data")
 *     .retrieve()
 *     .bodyToMono(String.class));
 *
 * // Protect with waiting (non-blocking delay)
 * Mono<String> result = limiter.executeWithWait(
 *     () -> webClient.get().uri("/api").retrieve().bodyToMono(String.class));
 *
 * // With fallback
 * Mono<String> result = limiter.executeWithFallback(
 *     webClient.get().uri("/api").retrieve().bodyToMono(String.class),
 *     error -> Mono.just("fallback"));
 *
 * // Operator style
 * Mono<String> result = webClient.get().uri("/api")
 *     .retrieve()
 *     .bodyToMono(String.class)
 *     .transformDeferred(limiter.monoOperator());
 *
 * // Listen to events
 * limiter.events().subscribe(event -> log.info("Rate limiter: {}", event));
 * }</pre>
 */
public class ReactiveRateLimiter {

  private final RateLimiterConfig config;
  private final AtomicReference<RateLimiterSnapshot> snapshotRef;
  private final Clock clock;
  private final Sinks.Many<RateLimiterEvent> eventSink;

  public ReactiveRateLimiter(RateLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ReactiveRateLimiter(RateLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(RateLimiterSnapshot.initial(config, clock.instant()));
    this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
  }

  // ======================== Mono execution — fail-fast ========================

  /**
   * Protects a {@link Mono} with the rate limiter (fail-fast).
   *
   * <p>If no permit is available, emits a {@link RateLimiterException}
   * immediately without waiting.
   *
   * @param mono the upstream mono to protect
   * @param <T>  the element type
   * @return a mono that is short-circuited when no permits are available
   */
  public <T> Mono<T> execute(Mono<T> mono) {
    return Mono.defer(() -> {
      RateLimitPermission permission = acquirePermission();
      if (!permission.permitted()) {
        return Mono.error(new RateLimiterException(
            config.name(), permission.waitDuration(),
            permission.snapshot().availablePermits()));
      }
      return mono;
    });
  }

  /**
   * Protects a lazily created {@link Mono} (fail-fast).
   */
  public <T> Mono<T> execute(Supplier<Mono<T>> monoSupplier) {
    return Mono.defer(() -> {
      RateLimitPermission permission = acquirePermission();
      if (!permission.permitted()) {
        return Mono.error(new RateLimiterException(
            config.name(), permission.waitDuration(),
            permission.snapshot().availablePermits()));
      }
      return monoSupplier.get();
    });
  }

  /**
   * Protects a {@link Mono} with a reactive fallback on rate limit.
   */
  public <T> Mono<T> executeWithFallback(
      Mono<T> mono,
      Function<Throwable, Mono<T>> fallbackFunction) {

    return execute(mono)
        .onErrorResume(RateLimiterException.class, fallbackFunction);
  }

  // ======================== Mono execution — with wait ========================

  /**
   * Protects a lazily created {@link Mono}, waiting non-blockingly
   * (via {@link Mono#delay}) when no permit is immediately available.
   *
   * <p>If the required wait exceeds the configured timeout, emits a
   * {@link RateLimiterException}.
   *
   * @param monoSupplier supplier that creates the mono after the permit is acquired
   * @param <T>          the element type
   * @return a mono that delays subscription until a permit is available
   */
  public <T> Mono<T> executeWithWait(Supplier<Mono<T>> monoSupplier) {
    return executeWithWait(monoSupplier, config.defaultTimeout());
  }

  /**
   * Protects a lazily created {@link Mono} with a custom timeout.
   */
  public <T> Mono<T> executeWithWait(Supplier<Mono<T>> monoSupplier, Duration timeout) {
    return Mono.defer(() -> {
      ReservationResult reservation = reservePermission(timeout);

      if (reservation.timedOut()) {
        return Mono.error(new RateLimiterException(
            config.name(), reservation.waitDuration(),
            snapshotRef.get().availablePermits()));
      }

      if (reservation.waitDuration().isZero()) {
        return monoSupplier.get();
      }

      // Non-blocking delay, then execute
      return Mono.delay(reservation.waitDuration())
          .then(Mono.defer(monoSupplier));
    });
  }

  // ======================== Flux execution ========================

  /**
   * Protects a {@link Flux} with the rate limiter (fail-fast).
   *
   * <p>A single permit is acquired for the entire flux subscription —
   * this limits the <em>start rate</em> of flux subscriptions, not
   * individual element emissions.
   */
  public <T> Flux<T> executeMany(Flux<T> flux) {
    return Flux.defer(() -> {
      RateLimitPermission permission = acquirePermission();
      if (!permission.permitted()) {
        return Flux.error(new RateLimiterException(
            config.name(), permission.waitDuration(),
            permission.snapshot().availablePermits()));
      }
      return flux;
    });
  }

  /**
   * Protects a {@link Flux} with a reactive fallback on rate limit.
   */
  public <T> Flux<T> executeManyWithFallback(
      Flux<T> flux,
      Function<Throwable, Publisher<T>> fallbackFunction) {

    return executeMany(flux)
        .onErrorResume(RateLimiterException.class, fallbackFunction);
  }

  // ======================== Operator style ========================

  /**
   * Returns a function for use with {@link Mono#transformDeferred}.
   *
   * <pre>{@code
   * webClient.get().uri("/api")
   *     .retrieve()
   *     .bodyToMono(String.class)
   *     .transformDeferred(limiter.monoOperator());
   * }</pre>
   */
  public <T> Function<Mono<T>, Mono<T>> monoOperator() {
    return this::execute;
  }

  /**
   * Returns a function for use with {@link Flux#transformDeferred}.
   */
  public <T> Function<Flux<T>, Flux<T>> fluxOperator() {
    return this::executeMany;
  }

  // ======================== Internal state management ========================

  private RateLimitPermission acquirePermission() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermission(current, config, now);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          emitEvent(RateLimiterEvent.rejected(
              config.name(), result.snapshot().availablePermits(),
              result.waitDuration(), now));
        }
        return result;
      }
      // CAS failed — retry
    }
  }

  private ReservationResult reservePermission(Duration timeout) {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      ReservationResult result = RateLimiterCore.reservePermission(
          current, config, now, timeout);

      if (result.timedOut()) {
        // No state change on timeout
        emitEvent(RateLimiterEvent.rejected(
            config.name(), current.availablePermits(), result.waitDuration(), now));
        return result;
      }

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.waitDuration().isZero()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          emitEvent(RateLimiterEvent.waiting(
              config.name(), result.snapshot().availablePermits(),
              result.waitDuration(), now));
        }
        return result;
      }
      // CAS failed — retry
    }
  }

  // ======================== Events ========================

  /**
   * Returns a hot {@link Flux} of rate limiter events.
   */
  public Flux<RateLimiterEvent> events() {
    return eventSink.asFlux();
  }

  private void emitEvent(RateLimiterEvent event) {
    eventSink.tryEmitNext(event);
  }

  // ======================== Introspection ========================

  /**
   * Returns the current number of available permits (with refill applied).
   */
  public int getAvailablePermits() {
    return RateLimiterCore.availablePermits(snapshotRef.get(), config, clock.instant());
  }

  /**
   * Returns a snapshot of the current internal state.
   */
  public RateLimiterSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  /**
   * Returns the configuration.
   */
  public RateLimiterConfig getConfig() {
    return config;
  }

  /**
   * Drains all permits.
   */
  public void drain() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot drained = RateLimiterCore.drain(current);
      if (snapshotRef.compareAndSet(current, drained)) {
        emitEvent(RateLimiterEvent.drained(config.name(), now));
        return;
      }
    }
  }

  /**
   * Resets to initial (full-bucket) state.
   */
  public void reset() {
    Instant now = clock.instant();
    RateLimiterSnapshot fresh = RateLimiterCore.reset(config, now);
    snapshotRef.set(fresh);
    emitEvent(RateLimiterEvent.reset(config.name(), fresh.availablePermits(), now));
  }
}
