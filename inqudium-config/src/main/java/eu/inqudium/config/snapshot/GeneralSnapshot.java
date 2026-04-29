package eu.inqudium.config.snapshot;

import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Objects;

/**
 * Runtime-level configuration snapshot — the cross-cutting collaborators every component shares.
 *
 * <p>Distinct from {@link ComponentSnapshot}: not a per-component value object, not part of the
 * sealed {@code ComponentSnapshot} hierarchy. The runtime exposes one {@code GeneralSnapshot}
 * via {@code InqRuntime.general()}; component-materialization code reads its collaborators
 * (clock, nanoTimeSource, eventPublisher, loggerFactory) when building component instances. This
 * is the single truth source for the cross-cutting collaborators an Inqudium runtime exposes.
 *
 * <p>The record carries only the fields the runtime actually needs at materialization time;
 * compatibility flags and other fields from the legacy {@code GeneralConfig} are not mirrored
 * here.
 *
 * @param clock                       wall-clock source for event timestamps.
 * @param nanoTimeSource              monotonic time source for RTT measurements and deadlines.
 * @param eventPublisher              the runtime-scoped event publisher used for lifecycle
 *                                    topology events ({@code ComponentBecameHotEvent},
 *                                    {@code RuntimeComponentAddedEvent}, ...). Per-call
 *                                    component events flow through the per-component publishers
 *                                    produced by {@code componentPublisherFactory}.
 * @param componentPublisherFactory   factory used at component-materialization time to provision
 *                                    per-component {@link InqEventPublisher}s. Per ADR-030,
 *                                    each live component owns its own publisher; this factory is
 *                                    how the runtime hands them out.
 * @param loggerFactory               factory for component logging.
 * @param enableExceptionOptimization whether resilience exceptions (rejections, interrupts,
 *                                    timeouts) suppress stack-trace generation per ADR-020 — a
 *                                    flow-control signal does not need a synthetic stack. The
 *                                    {@link RejectionContext}-style payload carries the
 *                                    diagnostic data instead. Defaults to {@code true} via the
 *                                    builder; component implementations read this flag at every
 *                                    construction site that throws an
 *                                    {@code InqException} on a flow-control path.
 */
public record GeneralSnapshot(
        InqClock clock,
        InqNanoTimeSource nanoTimeSource,
        InqEventPublisher eventPublisher,
        ComponentEventPublisherFactory componentPublisherFactory,
        LoggerFactory loggerFactory,
        boolean enableExceptionOptimization) {

    public GeneralSnapshot {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
        Objects.requireNonNull(eventPublisher, "eventPublisher");
        Objects.requireNonNull(componentPublisherFactory, "componentPublisherFactory");
        Objects.requireNonNull(loggerFactory, "loggerFactory");
    }
}
