package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;

import java.util.Map;

/**
 * Per-paradigm container surface — the registry-equivalent for one paradigm.
 *
 * <p>Each paradigm extends this interface with its own typed accessors ({@link Imperative} adds
 * {@code bulkhead(name)}, {@code findBulkhead(name)}, ...). The base interface carries only the
 * paradigm tag plus the runtime-update entry point so generic code can route updates to a
 * {@code ParadigmContainer<?>} without knowing the specific paradigm at compile time.
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
     * @return per-component outcomes, in the order of the input patches.
     */
    Map<String, ApplyOutcome> applyUpdate(GeneralSnapshot general, ParadigmSectionPatches patches);
}
