package eu.inqudium.config.snapshot;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

/**
 * Factory the runtime uses to provision a per-component {@link InqEventPublisher} at
 * component-materialization time.
 *
 * <p>Per ADR-030, every live component owns its own publisher rather than sharing the
 * runtime-scoped one. The factory takes the component's element name and type so each created
 * publisher carries the right identity for its events. The default supplied by
 * {@link eu.inqudium.config.dsl.GeneralSnapshotBuilder GeneralSnapshotBuilder} delegates to
 * {@link InqEventPublisher#create(String, InqElementType)}, binding to the global default
 * exporter registry — which matches the pre-refactor {@code ImperativeBulkhead} behaviour. Tests
 * inject custom factories to capture per-component events into isolated registries.
 *
 * <p>The interface is functional so a method reference is the typical implementation. A custom
 * factory can capture an isolated {@code InqEventExporterRegistry} per {@code InqRuntime} —
 * see {@code GeneralSnapshotBuilder.isolatedEventRegistry()} for the documented opt-in.
 */
@FunctionalInterface
public interface ComponentEventPublisherFactory {

    /**
     * @param elementName the component's stable name; non-null.
     * @param elementType the component's element type; non-null.
     * @return a new publisher bound to the component's identity.
     */
    InqEventPublisher create(String elementName, InqElementType elementType);
}
