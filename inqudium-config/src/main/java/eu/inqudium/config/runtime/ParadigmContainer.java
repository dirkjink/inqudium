package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Per-paradigm container surface — the registry-equivalent for one paradigm.
 *
 * <p>Each paradigm extends this interface with its own typed accessors ({@link Imperative} adds
 * {@code bulkhead(name)}, {@code findBulkhead(name)}, ...). The base interface carries the
 * paradigm tag, the runtime-update entry point, and a paradigm-agnostic snapshot stream so
 * cross-paradigm code in the runtime can iterate every component without branching on
 * container type.
 *
 * @param <P> the paradigm tag.
 */
public interface ParadigmContainer<P extends ParadigmTag> {

    /**
     * @return the tag identifying this container's paradigm.
     */
    ParadigmTag paradigm();

    /**
     * Apply a section's worth of update patches to this container.
     *
     * <p>For each {@code (name, patch)} entry: if the named component already exists, the patch
     * is applied to its live container and the outcome is reported as
     * {@link ApplyOutcome#PATCHED} (or {@link ApplyOutcome#UNCHANGED} when the patch was a
     * no-op). If the name is new, a fresh component is materialized through the paradigm
     * provider and added to the container; the outcome is {@link ApplyOutcome#ADDED}.
     *
     * <p>Phase&nbsp;1 supports only add and patch. {@link ApplyOutcome#REMOVED},
     * {@link ApplyOutcome#REJECTED}, and {@link ApplyOutcome#VETOED} arrive in phase&nbsp;2.
     *
     * @param general the runtime-level snapshot supplying clock and event publisher to any
     *                newly materialized components.
     * @param patches the patches to apply, in registration order.
     * @return per-component outcomes keyed by {@link ComponentKey}, in the order of the input
     *         patches. The container is responsible for tagging each outcome with its own
     *         paradigm so callers do not have to.
     */
    Map<ComponentKey, ApplyOutcome> applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches);

    /**
     * @return a stream over every component snapshot in this paradigm, in registration order.
     *         The default returns {@link Stream#empty()} so a paradigm with no exposed
     *         components contributes nothing to cross-paradigm views. Concrete containers
     *         override this to surface their snapshots — see
     *         {@code DefaultImperative#snapshots()}.
     */
    default Stream<? extends ComponentSnapshot> snapshots() {
        return Stream.empty();
    }
}
