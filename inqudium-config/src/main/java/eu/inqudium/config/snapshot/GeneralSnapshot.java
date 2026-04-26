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
 * is the single truth source for the cross-cutting collaborators — see clarification&nbsp;4 in
 * {@code REFACTORING.md}.
 *
 * <p>Phase&nbsp;1 stays minimal: only the four fields actually consumed in phase&nbsp;1 are
 * present. Compatibility flags and other fields from the old {@code GeneralConfig} are deferred
 * until a concrete consumer needs them. The old {@code GeneralConfig} is deprecated in
 * step&nbsp;1.10, not earlier.
 *
 * @param clock           wall-clock source for event timestamps.
 * @param nanoTimeSource  monotonic time source for RTT measurements and deadlines.
 * @param eventPublisher  the runtime-scoped event publisher used for lifecycle topology events
 *                        ({@code ComponentBecameHotEvent}, {@code RuntimeComponentAddedEvent},
 *                        ...). Per-component publishers (carrying {@code BulkheadOnAcquireEvent}
 *                        etc., per ADR-003) are introduced together with the bulkhead-event
 *                        port in step&nbsp;1.9 — at that point this field becomes a
 *                        {@code PublisherFactory} or gains a sibling factory field.
 * @param loggerFactory   factory for component logging.
 */
public record GeneralSnapshot(
        InqClock clock,
        InqNanoTimeSource nanoTimeSource,
        InqEventPublisher eventPublisher,
        LoggerFactory loggerFactory) {

    public GeneralSnapshot {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
        Objects.requireNonNull(eventPublisher, "eventPublisher");
        Objects.requireNonNull(loggerFactory, "loggerFactory");
    }
}
