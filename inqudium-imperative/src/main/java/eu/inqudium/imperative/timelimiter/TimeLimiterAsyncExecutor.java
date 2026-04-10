package eu.inqudium.imperative.timelimiter;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Strategy interface for non-blocking time-limited execution.
 *
 * <p>Implementations define <em>how</em> a callable, future, or completion stage
 * is executed asynchronously with a timeout. The {@link ImperativeTimeLimiter}
 * delegates to this interface for all {@code execute*Async} methods.
 *
 * <p>The default implementation ({@link CompletableFutureAsyncExecutor}) uses
 * {@link CompletableFuture#orTimeout} and {@link CompletableFuture#handle} to
 * attach timeout enforcement and event emission to the pipeline. Alternative
 * implementations could use reactive types, a scheduling framework, or custom
 * timeout wheels.
 *
 * <p>Implementations receive a {@link TimeLimiterContext} at construction time
 * for access to configuration, event emission, and shared infrastructure.
 *
 * @see CompletableFutureAsyncExecutor
 */
public interface TimeLimiterAsyncExecutor {

    /**
     * Executes the callable asynchronously with a timeout.
     *
     * @param callable the operation to execute
     * @param timeout  the maximum duration before timeout
     * @return a future that completes with the result or a timeout/failure exception
     */
    <T> CompletableFuture<T> executeAsync(Callable<T> callable, Duration timeout);

    /**
     * Bridges an external {@link Future} into a timeout-protected {@link CompletableFuture}.
     *
     * @param futureSupplier supplier for the already-running future
     * @param timeout        the maximum duration before timeout
     * @return a future that completes with the result or a timeout/failure exception
     */
    <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier, Duration timeout);

    /**
     * Attaches timeout protection to an existing {@link CompletionStage} pipeline.
     *
     * @param stageSupplier supplier for the already-running completion stage
     * @param timeout       the maximum duration before timeout
     * @return a future with timeout, events, and exception transformation attached
     */
    <T> CompletableFuture<T> executeCompletionStageAsync(
            Supplier<CompletionStage<T>> stageSupplier, Duration timeout);
}
