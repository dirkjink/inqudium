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
 * <p>This module depends on {@code inqudium-core} for the wrapper infrastructure
 * and on AspectJ for the advice annotations.</p>
 */
package eu.inqudium.aspect.pipeline;
