package eu.inqudium.config.runtime;

import eu.inqudium.core.pipeline.InternalExecutor;

/**
 * Imperative-paradigm bulkhead handle.
 *
 * <p>Adds the synchronous {@code execute} entry point to the paradigm-agnostic
 * {@link BulkheadHandle} contract. Calls of any argument and return type pass through this
 * single execute method via method-level type parameters; the bulkhead itself is not
 * parameterized on {@code A}/{@code R} because one component instance dispatches calls of
 * many shapes throughout its lifetime.
 *
 * <p>Implemented by {@code InqBulkhead} in {@code inqudium-imperative}. The runtime's
 * {@link Imperative} container returns this interface from {@link Imperative#bulkhead(String)}
 * so the {@code inqudium-config} surface never imports the concrete imperative class.
 */
public interface ImperativeBulkhead extends BulkheadHandle<ImperativeTag> {

    /**
     * Execute the next layer of the chain through this bulkhead.
     *
     * @param chainId  the chain identifier of the call.
     * @param callId   the call identifier.
     * @param argument the argument flowing through the chain.
     * @param next     the next executor in the chain.
     * @param <A>      the argument type.
     * @param <R>      the return type.
     * @return the value produced by the chain after passing through the bulkhead.
     */
    <A, R> R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
}
