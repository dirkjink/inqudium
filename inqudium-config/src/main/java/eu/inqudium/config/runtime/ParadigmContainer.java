package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;

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
     * is routed through the {@link UpdateDispatcher}, which decides cold-path apply versus
     * hot-path veto chain (ADR-028). The dispatcher returns either an
     * {@link ApplyOutcome#PATCHED PATCHED}/{@link ApplyOutcome#UNCHANGED UNCHANGED} verdict or
     * a {@link ApplyOutcome#VETOED VETOED} verdict with the corresponding
     * {@link eu.inqudium.config.validation.VetoFinding VetoFinding}. If the name is new, a fresh
     * component is materialized through the paradigm provider and added to the container; the
     * outcome is {@link ApplyOutcome#ADDED ADDED}.
     *
     * @param general the runtime-level snapshot supplying clock and event publisher to any
     *                newly materialized components.
     * @param patches the patches to apply, in registration order.
     * @return the per-component outcomes plus any veto findings emitted along the way. The
     *         container is responsible for tagging each outcome with its own paradigm so callers
     *         do not have to.
     */
    ParadigmApplyResult applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches);

    /**
     * Run the same validation and veto chain as {@link #applyUpdate} but produce no side effects.
     * The container does not insert, mutate, or remove any component, does not materialize new
     * components beyond the snapshot construction needed to validate them, and does not publish
     * any topology event on the runtime publisher.
     *
     * <p>Drives {@code runtime.dryRun(...)}: callers receive the per-component outcomes and
     * veto findings the corresponding {@link #applyUpdate} call would have produced for the
     * runtime's current state.
     *
     * @param general the runtime-level snapshot supplying clock and event publisher to any
     *                hypothetically materialized components — only consulted for validation, not
     *                for actually wiring publishers.
     * @param patches the patches to evaluate, in registration order.
     * @return the per-component outcomes plus any veto findings, identical in shape to
     *         {@link #applyUpdate}'s return value.
     */
    ParadigmApplyResult dryRunUpdate(
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
