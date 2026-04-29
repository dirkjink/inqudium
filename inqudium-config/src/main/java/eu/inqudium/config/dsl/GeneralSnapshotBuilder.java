package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.ComponentEventPublisherFactory;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
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
    private ComponentEventPublisherFactory componentPublisherFactory;
    private LoggerFactory loggerFactory;
    private Boolean enableExceptionOptimization;

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
     * {@code RuntimeComponentAddedEvent}.
     *
     * <p>The publisher's <em>exporter-registry binding</em> is whatever the caller chose at
     * construction time: the two-argument {@link InqEventPublisher#create(String, InqElementType)
     * create(name, type)} factory binds to the global
     * {@link InqEventExporterRegistry#getDefault() default registry} (the standard form), while
     * the four-argument {@link InqEventPublisher#create(String, InqElementType,
     * InqEventExporterRegistry, InqPublisherConfig) create(name, type, registry, config)} variant
     * binds to whichever registry the caller passes in. For per-runtime isolation against a
     * fresh registry, prefer {@link #isolatedEventRegistry()} — it does the wiring in one step
     * and keeps the runtime and component publishers on the same registry.
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
     * Toggle the exception-optimization performance feature documented in ADR-020. When enabled
     * (the default), resilience exceptions thrown on flow-control paths — bulkhead rejections,
     * bulkhead interrupts — suppress stack-trace generation. The {@link
     * eu.inqudium.core.element.bulkhead.strategy.RejectionContext}-style payload carries the
     * diagnostic data instead, and the rejection path stops paying the cost of
     * {@link Throwable#fillInStackTrace()} under high reject rates.
     *
     * @param enableExceptionOptimization {@code true} to enable, {@code false} to keep full
     *                                    stack-trace generation on rejection paths.
     * @return this builder.
     */
    public GeneralSnapshotBuilder enableExceptionOptimization(boolean enableExceptionOptimization) {
        this.enableExceptionOptimization = enableExceptionOptimization;
        return this;
    }

    /**
     * Override the per-component publisher factory. Per ADR-030 every live component owns its
     * own {@link InqEventPublisher}; the factory is what hands them out at component-
     * materialization time.
     *
     * <p>The default factory delegates to {@link InqEventPublisher#create(String, InqElementType)}
     * which binds to the global {@link InqEventExporterRegistry#getDefault() default registry} —
     * the pre-refactor behaviour. A custom factory is itself a closure that decides which
     * registry to pass to {@code InqEventPublisher.create(...)}; passing the global default keeps
     * the shared-registry contract, passing a private {@link InqEventExporterRegistry} isolates
     * this runtime's component events from every other runtime. For the common
     * "fresh registry, both publishers wired" case, prefer {@link #isolatedEventRegistry()}.
     *
     * @param componentPublisherFactory the factory; non-null.
     * @return this builder.
     */
    public GeneralSnapshotBuilder componentPublisherFactory(
            ComponentEventPublisherFactory componentPublisherFactory) {
        this.componentPublisherFactory =
                Objects.requireNonNull(componentPublisherFactory, "componentPublisherFactory");
        return this;
    }

    /**
     * Bind both the runtime-scoped publisher and the component-publisher factory to a fresh,
     * private {@link InqEventExporterRegistry}. This is the opt-in for the multi-runtime
     * scenario described in ADR-031: every event published through this {@code GeneralSnapshot}
     * — runtime topology events <em>and</em> per-component events from every component the
     * factory mints — flows through the same isolated registry, and no event leaks to the
     * global default.
     *
     * <p>Composes:
     *
     * <ul>
     *   <li>a freshly allocated {@code InqEventExporterRegistry} (no
     *       {@link InqEventExporterRegistry#setDefault(InqEventExporterRegistry) setDefault}
     *       side effect — the global default registry is untouched);</li>
     *   <li>a runtime publisher built via the four-argument
     *       {@link InqEventPublisher#create(String, InqElementType, InqEventExporterRegistry,
     *       InqPublisherConfig) create(name, type, registry, config)} with the documented
     *       {@code "inqudium-runtime"} name, {@link InqElementType#NO_ELEMENT}, the new
     *       registry, and {@link InqPublisherConfig#defaultConfig()};</li>
     *   <li>a {@link ComponentEventPublisherFactory} closure that captures the same registry
     *       and uses the same {@code create(name, type, registry, config)} factory for every
     *       component publisher it produces.</li>
     * </ul>
     *
     * <p>The two values are written through the existing {@link #eventPublisher(InqEventPublisher)}
     * and {@link #componentPublisherFactory(ComponentEventPublisherFactory)} setters — calling
     * either after this method overwrites the corresponding field with the caller's value, and
     * calling this method twice replaces the registry of the first call (last-writer-wins,
     * matching every other setter on this builder).
     *
     * @return this builder.
     */
    public GeneralSnapshotBuilder isolatedEventRegistry() {
        InqEventExporterRegistry registry = new InqEventExporterRegistry();
        InqPublisherConfig config = InqPublisherConfig.defaultConfig();
        InqEventPublisher runtimePublisher = InqEventPublisher.create(
                DEFAULT_RUNTIME_PUBLISHER_NAME, InqElementType.NO_ELEMENT, registry, config);
        ComponentEventPublisherFactory factory =
                (name, type) -> InqEventPublisher.create(name, type, registry, config);
        return eventPublisher(runtimePublisher)
                .componentPublisherFactory(factory);
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
        ComponentEventPublisherFactory f = componentPublisherFactory != null
                ? componentPublisherFactory
                : InqEventPublisher::create;
        LoggerFactory l = loggerFactory != null ? loggerFactory : LoggerFactory.NO_OP_LOGGER_FACTORY;
        boolean optimize = enableExceptionOptimization == null || enableExceptionOptimization;
        return new GeneralSnapshot(c, n, p, f, l, optimize);
    }
}
