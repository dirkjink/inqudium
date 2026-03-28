/**
 * Pipeline composition — fluent API for combining resilience elements with
 * explicit, validated ordering.
 *
 * <p>The pipeline solves a specific problem: composing multiple elements into a
 * single decoration chain where the ordering is explicit, readable, and validated
 * for known anti-patterns (ADR-017).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code InqPipeline} — fluent builder: {@code InqPipeline.of(supplier).shield(cb).shield(retry).decorate()}.
 *       Elements can be added in any order when a {@code PipelineOrder} is set — the
 *       pipeline sorts them.</li>
 *   <li>{@code InqDecorator} — decoration contract implemented by each element.</li>
 *   <li>{@code PipelineOrder} — predefined orderings:
 *       <ul>
 *         <li>{@code INQUDIUM} — canonical: Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry</li>
 *         <li>{@code RESILIENCE4J} — compatible: Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead</li>
 *         <li>{@code custom(...)} — fully custom order via {@code InqElementType} sequence</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Pipeline responsibilities beyond ordering</h2>
 * <ul>
 *   <li>Generates the {@code callId} (ADR-003) at the outermost element.</li>
 *   <li>Orchestrates context propagation (ADR-011) across all elements.</li>
 *   <li>Emits startup warnings for known anti-patterns (e.g. Retry outside CircuitBreaker).</li>
 * </ul>
 *
 * <h2>Annotation-driven pipelines</h2>
 * <p>In Spring Boot, the {@code @InqShield} annotation controls ordering while
 * element annotations ({@code @InqCircuitBreaker}, {@code @InqRetry}, etc.) declare
 * which elements to apply. Without {@code @InqShield}, the canonical order is used
 * implicitly (ADR-017).
 *
 * @see eu.inqudium.core.InqElementType
 */
package eu.inqudium.core.pipeline;
