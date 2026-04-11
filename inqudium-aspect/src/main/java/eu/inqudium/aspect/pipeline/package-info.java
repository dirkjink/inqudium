/**
 * AspectJ integration for the inqudium wrapper pipeline.
 *
 * <p>This module bridges AspectJ's {@code @Around} advice with the core
 * {@link eu.inqudium.core.pipeline.JoinPointWrapper} chain. It provides:</p>
 *
 * <ul>
 *   <li>{@link eu.inqudium.aspect.pipeline.AspectLayerProvider} — declarative
 *       interface for contributing layers with priority ordering and
 *       method-specific filtering via {@code canHandle(Method)}.</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AspectPipelineBuilder} — assembles
 *       providers into a {@code JoinPointWrapper} chain (cold path, with
 *       full {@link eu.inqudium.core.pipeline.Wrapper} introspection).</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.ResolvedPipeline} — pre-composed,
 *       cached pipeline for hot-path execution (no per-call wrapper allocation).</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AbstractPipelineAspect} — abstract
 *       base class that caches a {@code ResolvedPipeline} per {@code Method}
 *       and provides both hot-path and cold-path execution/introspection.</li>
 * </ul>
 *
 * <h3>Asynchronous support</h3>
 * <p>For proxied methods that return a {@link java.util.concurrent.CompletionStage},
 * the module provides async counterparts:</p>
 * <ul>
 *   <li>{@link eu.inqudium.aspect.pipeline.AsyncAspectLayerProvider} — contributes
 *       an {@link eu.inqudium.imperative.core.pipeline.AsyncLayerAction} with
 *       two-phase around-semantics (synchronous start, asynchronous end).</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AsyncAspectPipelineBuilder} — assembles
 *       providers into an {@code AsyncJoinPointWrapper} chain (cold path).</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AsyncResolvedPipeline} — pre-composed,
 *       cached async pipeline for hot-path execution.</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AbstractAsyncPipelineAspect} — abstract
 *       base class that caches an {@code AsyncResolvedPipeline} per {@code Method}.</li>
 * </ul>
 *
 * <p>This module depends on {@code inqudium-core} for the wrapper infrastructure,
 * {@code inqudium-imperative} for the async pipeline classes,
 * and on AspectJ for the advice annotations.</p>
 */
package eu.inqudium.aspect.pipeline;
