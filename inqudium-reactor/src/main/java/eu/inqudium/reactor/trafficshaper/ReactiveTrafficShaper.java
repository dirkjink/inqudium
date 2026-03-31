package eu.inqudium.reactor.trafficshaper;

import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.ThrottleSnapshot;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.trafficshaper.TrafficShaperCore;
import eu.inqudium.core.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.trafficshaper.TrafficShaperException;
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
 * Non-blocking, reactive traffic shaper implementation for use with
 * Project Reactor (Spring WebFlux).
 *
 * <p>Uses lock-free CAS for scheduling and {@link Mono#delay} for
 * non-blocking throttle waits, keeping the event loop free.
 *
 * <p>Delegates all scheduling logic to the functional
 * {@link TrafficShaperCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = TrafficShaperConfig.builder("api-shaper")
 *     .ratePerSecond(100)
 *     .maxQueueDepth(200)
 *     .build();
 *
 * var shaper = new ReactiveTrafficShaper(config);
 *
 * // Protect a Mono — request is delayed to maintain even spacing
 * Mono<String> result = shaper.execute(webClient.get()
 *     .uri("/api/data")
 *     .retrieve()
 *     .bodyToMono(String.class));
 *
 * // With fallback on overflow
 * Mono<String> result = shaper.executeWithFallback(
 *     webClient.get().uri("/api").retrieve().bodyToMono(String.class),
 *     error -> Mono.just("overflow-fallback"));
 *
 * // Operator style
 * Mono<String> result = webClient.get().uri("/api")
 *     .retrieve()
 *     .bodyToMono(String.class)
 *     .transformDeferred(shaper.monoOperator());
 *
 * // Events
 * shaper.events().subscribe(event -> log.info("TrafficShaper: {}", event));
 * }</pre>
 */
public class ReactiveTrafficShaper {

  private final TrafficShaperConfig config;
  private final AtomicReference<ThrottleSnapshot> snapshotRef;
  private final Clock clock;
  private final Sinks.Many<TrafficShaperEvent> eventSink;

  public ReactiveTrafficShaper(TrafficShaperConfig config) {
    this(config, Clock.systemUTC());
  }

  public ReactiveTrafficShaper(TrafficShaperConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(ThrottleSnapshot.initial(clock.instant()));
    this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
  }

  // ======================== Mono execution ========================

  /**
   * Protects a {@link Mono} with traffic shaping.
   *
   * <p>If a delay is required, the upstream subscription is deferred
   * via {@link Mono#delay} until the assigned slot arrives.
   */
  public <T> Mono<T> execute(Mono<T> mono) {
    return Mono.defer(() -> {
      ThrottlePermission permission = acquireSlot();

      if (!permission.admitted()) {
        return Mono.error(new TrafficShaperException(
            config.name(), permission.waitDuration(),
            permission.snapshot().queueDepth()));
      }

      Mono<T> shaped = mono.doFinally(e -> recordExecution());

      if (permission.requiresWait()) {
        return Mono.delay(permission.waitDuration()).then(shaped);
      }
      return shaped;
    });
  }

  /**
   * Protects a lazily created {@link Mono} with traffic shaping.
   */
  public <T> Mono<T> execute(Supplier<Mono<T>> monoSupplier) {
    return Mono.defer(() -> {
      ThrottlePermission permission = acquireSlot();

      if (!permission.admitted()) {
        return Mono.error(new TrafficShaperException(
            config.name(), permission.waitDuration(),
            permission.snapshot().queueDepth()));
      }

      Supplier<Mono<T>> wrappedSupplier = () ->
          monoSupplier.get().doFinally(e -> recordExecution());

      if (permission.requiresWait()) {
        return Mono.delay(permission.waitDuration())
            .then(Mono.defer(wrappedSupplier));
      }
      return wrappedSupplier.get();
    });
  }

  /**
   * Protects a {@link Mono} with a reactive fallback on overflow.
   */
  public <T> Mono<T> executeWithFallback(
      Mono<T> mono,
      Function<Throwable, Mono<T>> fallbackFunction) {

    return execute(mono)
        .onErrorResume(TrafficShaperException.class, fallbackFunction);
  }

  // ======================== Flux execution ========================

  /**
   * Protects a {@link Flux} with traffic shaping.
   *
   * <p>One slot is consumed for the entire flux subscription (not
   * per element). This shapes the <em>start rate</em> of flux
   * subscriptions.
   */
  public <T> Flux<T> executeMany(Flux<T> flux) {
    return Flux.defer(() -> {
      ThrottlePermission permission = acquireSlot();

      if (!permission.admitted()) {
        return Flux.error(new TrafficShaperException(
            config.name(), permission.waitDuration(),
            permission.snapshot().queueDepth()));
      }

      Flux<T> shaped = flux.doFinally(e -> recordExecution());

      if (permission.requiresWait()) {
        return Mono.delay(permission.waitDuration()).thenMany(shaped);
      }
      return shaped;
    });
  }

  /**
   * Protects a {@link Flux} with a reactive fallback on overflow.
   */
  public <T> Flux<T> executeManyWithFallback(
      Flux<T> flux,
      Function<Throwable, Publisher<T>> fallbackFunction) {

    return executeMany(flux)
        .onErrorResume(TrafficShaperException.class, fallbackFunction);
  }

  // ======================== Operator style ========================

  /**
   * Returns a function for use with {@link Mono#transformDeferred}.
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

  // ======================== Internal ========================

  private ThrottlePermission acquireSlot() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottlePermission permission = TrafficShaperCore.schedule(current, config, now);

      if (snapshotRef.compareAndSet(current, permission.snapshot())) {
        if (!permission.admitted()) {
          emitEvent(TrafficShaperEvent.rejected(
              config.name(), permission.waitDuration(),
              permission.snapshot().queueDepth(), now));
        } else if (permission.requiresWait()) {
          emitEvent(TrafficShaperEvent.admittedDelayed(
              config.name(), permission.waitDuration(),
              permission.snapshot().queueDepth(), now));
        } else {
          emitEvent(TrafficShaperEvent.admittedImmediate(
              config.name(), permission.snapshot().queueDepth(), now));
        }
        return permission;
      }
      // CAS failed — retry
    }
  }

  private void recordExecution() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot updated = TrafficShaperCore.recordExecution(current);
      if (snapshotRef.compareAndSet(current, updated)) {
        emitEvent(TrafficShaperEvent.executing(
            config.name(), updated.queueDepth(), now));
        return;
      }
    }
  }

  // ======================== Events ========================

  /**
   * Returns a hot {@link Flux} of traffic shaper events.
   */
  public Flux<TrafficShaperEvent> events() {
    return eventSink.asFlux();
  }

  private void emitEvent(TrafficShaperEvent event) {
    eventSink.tryEmitNext(event);
  }

  // ======================== Introspection ========================

  public int getQueueDepth() {
    return snapshotRef.get().queueDepth();
  }

  public Duration getEstimatedWait() {
    return TrafficShaperCore.estimateWait(snapshotRef.get(), clock.instant());
  }

  public ThrottleSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  public TrafficShaperConfig getConfig() {
    return config;
  }

  public void reset() {
    Instant now = clock.instant();
    snapshotRef.set(TrafficShaperCore.reset(now));
    emitEvent(TrafficShaperEvent.reset(config.name(), now));
  }
}
