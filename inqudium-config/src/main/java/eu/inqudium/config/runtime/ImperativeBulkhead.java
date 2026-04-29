package eu.inqudium.config.runtime;

/**
 * Imperative-paradigm bulkhead handle (transitional marker).
 *
 * <p>Today this interface only specialises {@link BulkheadHandle} to the imperative paradigm tag;
 * the synchronous {@code execute} entry point that used to live here was promoted to the type-level
 * generic form on {@code InqBulkhead<A, R>} as part of ADR-033 Stage 2. Since the method-level
 * generic and the type-level generic forms have the same JVM erasure and Java cannot support
 * both simultaneously on a single class, the declaration moved to the concrete component.
 *
 * <p>Stage 3 of ADR-033 deletes this interface entirely; consumers will type against
 * {@link BulkheadHandle}{@code <ImperativeTag>} directly. The interface is kept here for one
 * stage so the Spring, AspectJ, and annotation-support modules' imports do not change while the
 * Generic propagation settles.
 *
 * <p>Implemented by {@code InqBulkhead} in {@code inqudium-imperative}. The runtime's
 * {@link Imperative} container returns this interface from {@link Imperative#bulkhead(String)};
 * callers that need to invoke the synchronous chain entry point cast to
 * {@code InqBulkhead<Object, Object>} (the canonical type-erased instantiation) for the
 * remainder of Stage 2.
 */
public interface ImperativeBulkhead extends BulkheadHandle<ImperativeTag> {
}
