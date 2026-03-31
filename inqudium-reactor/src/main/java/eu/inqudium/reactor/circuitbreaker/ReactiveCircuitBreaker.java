package eu.inqudium.reactor.circuitbreaker;

import eu.inqudium.core.circuitbreaker.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Non-blocking, reactive circuit breaker implementation for use with
 * Project Reactor (Spring WebFlux).
 *
 * <p>Uses the same lock-free CAS approach as the imperative variant,
 * but wraps operations in {@link Mono} and {@link Flux} to stay fully
 * non-blocking. State transition events are emitted via a Reactor
 * {@link Sinks.Many}.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link CircuitBreakerCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = CircuitBreakerConfig.builder("my-service")
 *     .failureThreshold(3)
 *     .waitDurationInOpenState(Duration.ofSeconds(10))
 *     .build();
 *
 * var cb = new ReactiveCircuitBreaker(config);
 *
 * // Protect a Mono
 * Mono<String> result = cb.execute(webClient.get()
 *     .uri("/api/data")
 *     .retrieve()
 *     .bodyToMono(String.class));
 *
 * // With fallback
 * Mono<String> result = cb.executeWithFallback(
 *     webClient.get().uri("/api/data").retrieve().bodyToMono(String.class),
 *     throwable -> Mono.just("fallback")
 * );
 *
 * // Protect a Flux
 * Flux<Item> items = cb.executeMany(webClient.get()
 *     .uri("/api/items")
 *     .retrieve()
 *     .bodyToFlux(Item.class));
 *
 * // Listen to state transitions
 * cb.transitionEvents().subscribe(event ->
 *     log.info("Transition: {}", event));
 * }</pre>
 */
public class ReactiveCircuitBreaker {

    private final CircuitBreakerConfig config;
    private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
    private final Clock clock;
    private final Sinks.Many<StateTransition> transitionSink;

    public ReactiveCircuitBreaker(CircuitBreakerConfig config) {
        this(config, Clock.systemUTC());
    }

    public ReactiveCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(clock.instant()));
        this.transitionSink = Sinks.many().multicast().onBackpressureBuffer();
    }

    // ======================== Mono execution ========================

    /**
     * Protects a {@link Mono} with the circuit breaker.
     *
     * <p>Permission is acquired lazily on subscription via {@link Mono#defer}.
     *
     * @param mono the upstream mono to protect
     * @param <T>  the element type
     * @return a mono that is short-circuited when the circuit is open
     */
    public <T> Mono<T> execute(Mono<T> mono) {
        return Mono.defer(() -> {
            PermissionResult permission = acquirePermission();
            if (!permission.permitted()) {
                return Mono.error(new CircuitBreakerException(config.name(), snapshotRef.get().state()));
            }
            return mono
                    .doOnSuccess(e -> recordSuccess())
                    .doOnError(this::recordFailureIfApplicable);
        });
    }

    /**
     * Protects a {@link Mono} produced by a supplier.
     * Useful when the Mono itself should not be created if the circuit is open.
     *
     * @param monoSupplier supplier that creates the mono
     * @param <T>          the element type
     * @return a protected mono
     */
    public <T> Mono<T> execute(Supplier<Mono<T>> monoSupplier) {
        return Mono.defer(() -> {
            PermissionResult permission = acquirePermission();
            if (!permission.permitted()) {
                return Mono.error(new CircuitBreakerException(config.name(), snapshotRef.get().state()));
            }
            return monoSupplier.get()
                    .doOnSuccess(e -> recordSuccess())
                    .doOnError(this::recordFailureIfApplicable);
        });
    }

    /**
     * Protects a {@link Mono} with a reactive fallback on circuit open.
     *
     * @param mono             the upstream mono
     * @param fallbackFunction function that produces a fallback mono from the error
     * @param <T>              the element type
     * @return a mono that falls back when the circuit is open
     */
    public <T> Mono<T> executeWithFallback(
            Mono<T> mono,
            Function<Throwable, Mono<T>> fallbackFunction) {

        return execute(mono)
                .onErrorResume(CircuitBreakerException.class, fallbackFunction);
    }

    // ======================== Flux execution ========================

    /**
     * Protects a {@link Flux} with the circuit breaker.
     *
     * <p>A failure is recorded on the first error signal. Success is recorded
     * on completion without error.
     *
     * @param flux the upstream flux to protect
     * @param <T>  the element type
     * @return a flux that is short-circuited when the circuit is open
     */
    public <T> Flux<T> executeMany(Flux<T> flux) {
        return Flux.defer(() -> {
            PermissionResult permission = acquirePermission();
            if (!permission.permitted()) {
                return Flux.error(new CircuitBreakerException(config.name(), snapshotRef.get().state()));
            }
            return flux
                    .doOnComplete(this::recordSuccess)
                    .doOnError(this::recordFailureIfApplicable);
        });
    }

    /**
     * Protects a {@link Flux} with a reactive fallback on circuit open.
     *
     * @param flux             the upstream flux
     * @param fallbackFunction function producing the fallback publisher
     * @param <T>              the element type
     * @return a flux that falls back when the circuit is open
     */
    public <T> Flux<T> executeManyWithFallback(
            Flux<T> flux,
            Function<Throwable, Publisher<T>> fallbackFunction) {

        return executeMany(flux)
                .onErrorResume(CircuitBreakerException.class, fallbackFunction);
    }

    // ======================== Operator style ========================

    /**
     * Returns a function that can be used with {@link Mono#transformDeferred}
     * to apply the circuit breaker as a reactive operator.
     *
     * <pre>{@code
     * webClient.get().uri("/api")
     *     .retrieve()
     *     .bodyToMono(String.class)
     *     .transformDeferred(cb.monoOperator());
     * }</pre>
     */
    public <T> Function<Mono<T>, Mono<T>> monoOperator() {
        return this::execute;
    }

    /**
     * Returns a function that can be used with {@link Flux#transformDeferred}
     * to apply the circuit breaker as a reactive operator.
     */
    public <T> Function<Flux<T>, Flux<T>> fluxOperator() {
        return this::executeMany;
    }

    // ======================== Internal state management ========================

    private PermissionResult acquirePermission() {
        Instant now = clock.instant();
        while (true) {
            CircuitBreakerSnapshot current = snapshotRef.get();
            PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

            if (snapshotRef.compareAndSet(current, result.snapshot())) {
                emitTransitionIfChanged(current, result.snapshot(), now);
                return result;
            }
            // CAS failed — retry
        }
    }

    private void recordSuccess() {
        Instant now = clock.instant();
        while (true) {
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(current, config, now);
            if (snapshotRef.compareAndSet(current, updated)) {
                emitTransitionIfChanged(current, updated, now);
                return;
            }
        }
    }

    private void recordFailureIfApplicable(Throwable throwable) {
        if (!config.shouldRecordAsFailure(throwable)) {
            recordSuccess();
            return;
        }
        Instant now = clock.instant();
        while (true) {
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, config, now);
            if (snapshotRef.compareAndSet(current, updated)) {
                emitTransitionIfChanged(current, updated, now);
                return;
            }
        }
    }

    // ======================== Events ========================

    /**
     * Returns a hot {@link Flux} of state transition events.
     * New subscribers receive only transitions that occur after subscribing.
     */
    public Flux<StateTransition> transitionEvents() {
        return transitionSink.asFlux();
    }

    private void emitTransitionIfChanged(CircuitBreakerSnapshot before, CircuitBreakerSnapshot after, Instant now) {
        StateTransition transition = CircuitBreakerCore.detectTransition(config.name(), before, after, now);
        if (transition != null) {
            transitionSink.tryEmitNext(transition);
        }
    }

    // ======================== Introspection ========================

    /**
     * Returns the current state of the circuit breaker.
     */
    public CircuitState getState() {
        return snapshotRef.get().state();
    }

    /**
     * Returns a snapshot of the current internal state.
     */
    public CircuitBreakerSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    /**
     * Returns the configuration.
     */
    public CircuitBreakerConfig getConfig() {
        return config;
    }

    /**
     * Resets the circuit breaker to its initial CLOSED state.
     */
    public void reset() {
        Instant now = clock.instant();
        CircuitBreakerSnapshot before = snapshotRef.getAndSet(CircuitBreakerSnapshot.initial(now));
        emitTransitionIfChanged(before, snapshotRef.get(), now);
    }
}
