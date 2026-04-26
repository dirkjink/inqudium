package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Objects;

/**
 * Mutable builder for {@link GeneralSnapshot}.
 *
 * <p>Receives the user's {@code .general(g -> g.clock(...).loggerFactory(...))} block from the
 * top-level {@code Inqudium.configure()} entry point and materializes the snapshot via
 * {@link #build()}. Fields the user does not set fall back to sensible defaults — system clock
 * and nano-time source, a runtime-scoped {@link InqEventPublisher} with element name
 * {@code "inqudium-runtime"} and type {@link InqElementType#NO_ELEMENT}, and a no-op logger
 * factory.
 *
 * <p>Setters validate non-null arguments at the call site (ADR-027 class&nbsp;1) so the
 * exception's stack trace points at the user's setter call rather than at {@link #build()}.
 */
public final class GeneralSnapshotBuilder {

    /** Element name used for the default runtime publisher. */
    static final String DEFAULT_RUNTIME_PUBLISHER_NAME = "inqudium-runtime";

    private InqClock clock;
    private InqNanoTimeSource nanoTimeSource;
    private InqEventPublisher eventPublisher;
    private LoggerFactory loggerFactory;

    /**
     * @param clock the wall-clock source; non-null.
     * @return this builder.
     */
    public GeneralSnapshotBuilder clock(InqClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * @param nanoTimeSource the monotonic time source; non-null.
     * @return this builder.
     */
    public GeneralSnapshotBuilder nanoTimeSource(InqNanoTimeSource nanoTimeSource) {
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
        return this;
    }

    /**
     * Override the default runtime-scoped event publisher. The runtime publisher carries
     * lifecycle-topology events such as {@code ComponentBecameHotEvent} and
     * {@code RuntimeComponentAddedEvent}. Application code rarely sets this; tests use it to
     * isolate event observation from the global registry.
     *
     * @param eventPublisher the runtime-scoped publisher; non-null.
     * @return this builder.
     */
    public GeneralSnapshotBuilder eventPublisher(InqEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        return this;
    }

    /**
     * @param loggerFactory the logger factory used by component implementations; non-null.
     * @return this builder.
     */
    public GeneralSnapshotBuilder loggerFactory(LoggerFactory loggerFactory) {
        this.loggerFactory = Objects.requireNonNull(loggerFactory, "loggerFactory");
        return this;
    }

    /**
     * Materialize the snapshot. Unset fields fall back to defaults: system clock, system
     * nano-time source, a {@code "inqudium-runtime"} publisher, and the no-op logger factory.
     *
     * @return the resulting {@link GeneralSnapshot}.
     */
    public GeneralSnapshot build() {
        InqClock c = clock != null ? clock : InqClock.system();
        InqNanoTimeSource n = nanoTimeSource != null ? nanoTimeSource : InqNanoTimeSource.system();
        InqEventPublisher p = eventPublisher != null
                ? eventPublisher
                : InqEventPublisher.create(DEFAULT_RUNTIME_PUBLISHER_NAME, InqElementType.NO_ELEMENT);
        LoggerFactory l = loggerFactory != null ? loggerFactory : LoggerFactory.NO_OP_LOGGER_FACTORY;
        return new GeneralSnapshot(c, n, p, l);
    }
}
