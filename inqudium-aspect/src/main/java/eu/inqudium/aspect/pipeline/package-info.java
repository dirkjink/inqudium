/**
 * AspectJ integration for the inqudium wrapper pipeline.
 *
 * <p>This module bridges AspectJ's {@code @Around} advice with the core
 * {@link eu.inqudium.core.pipeline.JoinPointWrapper} chain. It provides:</p>
 *
 * <ul>
 *   <li>{@link eu.inqudium.aspect.pipeline.AspectLayerProvider} — declarative
 *       interface for contributing layers with priority ordering.</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AspectPipelineBuilder} — assembles
 *       providers into a {@code JoinPointWrapper} chain, built inside-out.</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AbstractPipelineAspect} — abstract
 *       base class for aspects that wires providers, chain construction, and
 *       execution into a single {@code executeThrough(pjp::proceed)} call.</li>
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
 *       providers into an {@code AsyncJoinPointWrapper} chain.</li>
 *   <li>{@link eu.inqudium.aspect.pipeline.AbstractAsyncPipelineAspect} — abstract
 *       base class for async aspects, wiring into
 *       {@code executeThroughAsync(pjp::proceed)}.</li>
 * </ul>
 *
 * <p>This module depends on {@code inqudium-core} for the wrapper infrastructure,
 * {@code inqudium-imperative} for the async pipeline classes,
 * and on AspectJ for the advice annotations.</p>
 */
package eu.inqudium.aspect.pipeline;
