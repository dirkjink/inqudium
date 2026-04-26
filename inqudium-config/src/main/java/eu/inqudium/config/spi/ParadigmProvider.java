package eu.inqudium.config.spi;

import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.snapshot.GeneralSnapshot;

/**
 * Service-provider interface that links a paradigm module ({@code inqudium-imperative},
 * {@code inqudium-reactive}, {@code inqudium-rxjava3}, {@code inqudium-kotlin}) into the
 * runtime.
 *
 * <p>Each paradigm contributes one provider, registered via
 * {@code META-INF/services/eu.inqudium.config.spi.ParadigmProvider}. At runtime build time, the
 * top-level builder loads providers via {@link java.util.ServiceLoader}, matches each declared
 * paradigm section to the corresponding provider, and asks the provider to materialize a
 * {@link ParadigmContainer} from the section's accumulated patches plus the
 * {@link GeneralSnapshot}.
 *
 * <p>If a paradigm is referenced in the DSL but no provider for it is on the classpath, the
 * runtime raises {@link eu.inqudium.config.runtime.ParadigmUnavailableException
 * ParadigmUnavailableException} with a message naming the missing module.
 */
public interface ParadigmProvider {

    /**
     * @return the tag identifying the paradigm this provider materializes.
     */
    ParadigmTag paradigm();

    /**
     * Materialize a paradigm container from the given general snapshot and the patches the DSL
     * accumulated for this paradigm's section.
     *
     * @param general the runtime-level configuration (clock, event publisher, ...).
     * @param patches the patches for this paradigm's section, in registration order.
     * @return a paradigm container holding the materialized components.
     */
    ParadigmContainer<?> createContainer(GeneralSnapshot general, ParadigmSectionPatches patches);
}
