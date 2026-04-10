package eu.inqudium.imperative.timelimiter;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Strategy interface for blocking time-limited execution.
 *
 * <p>Implementations define <em>how</em> a callable, future, or completion stage
 * is executed with a timeout. The {@link ImperativeTimeLimiter} delegates to this
 * interface for all blocking {@code execute*} methods.
 *
 * <p>The default implementation ({@link VirtualThreadSyncExecutor}) spawns a virtual
 * thread and blocks via {@code Future.get(timeout)}. Alternative implementations
 * could use platform threads, a thread pool, or a deadline-check approach.
 *
 * <p>Implementations receive a {@link TimeLimiterContext} at construction time
 * for access to configuration, event emission, and shared infrastructure.
 *
 * @see VirtualThreadSyncExecutor
 */
public interface TimeLimiterSyncExecutor {

    /**
     * Executes the callable with a timeout, blocking the caller until completion.
     *
     * @param callable the operation to execute
     * @param timeout  the maximum duration to wait
     * @return the callable's result
     * @throws Exception if the callable fails, the timeout expires, or the thread is interrupted
     */
    <T> T execute(Callable<T> callable, Duration timeout) throws Exception;

    /**
     * Awaits an external {@link Future} with a timeout, blocking the caller.
     *
     * @param futureSupplier supplier for the already-running future
     * @param timeout        the maximum duration to wait
     * @return the future's result
     * @throws Exception if the future fails, the timeout expires, or the thread is interrupted
     */
    <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception;

    /**
     * Awaits a {@link CompletionStage} with a timeout, blocking the caller.
     *
     * @param stageSupplier supplier for the already-running completion stage
     * @param timeout       the maximum duration to wait
     * @return the stage's result
     * @throws Exception if the stage fails, the timeout expires, or the thread is interrupted
     */
    <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier, Duration timeout) throws Exception;
}
