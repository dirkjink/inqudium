package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.runtime.ParadigmUnavailableException;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqudiumUpdateBuilder}.
 *
 * <p>Re-uses {@link DefaultImperativeSection} for patch accumulation — the update path uses
 * the same DSL section types as initial configuration (per ADR-025: same builders, only the
 * starting snapshot differs). After the user's lambda finishes, {@link #toSectionPatches()}
 * yields the accumulated per-paradigm patches that {@code runtime.update} dispatches to each
 * paradigm container's {@code applyUpdate} entry point.
 *
 * <p>Receives the providers from {@code DefaultInqRuntime} so the same {@code ParadigmProvider}
 * instances that built the runtime also build the update sections. This keeps a single source
 * of truth for paradigm-specific factories.
 */
public final class DefaultInqudiumUpdateBuilder implements InqudiumUpdateBuilder {

    private final Map<ParadigmTag, ParadigmProvider> providers;
    private DefaultImperativeSection imperativeSection;

    public DefaultInqudiumUpdateBuilder(Map<ParadigmTag, ParadigmProvider> providers) {
        this.providers = providers;
    }

    @Override
    public InqudiumUpdateBuilder imperative(Consumer<ImperativeSection> configurer) {
        ParadigmProvider provider = providers.get(ImperativeTag.INSTANCE);
        if (provider == null) {
            throw new ParadigmUnavailableException(
                    "The 'imperative' paradigm requires module 'inqudium-imperative' on the "
                            + "classpath.");
        }
        if (imperativeSection == null) {
            imperativeSection = new DefaultImperativeSection(provider);
        }
        configurer.accept(imperativeSection);
        return this;
    }

    /**
     * @return the accumulated patches grouped by paradigm tag, in declaration order.
     */
    public Map<ParadigmTag, ParadigmSectionPatches> toSectionPatches() {
        Map<ParadigmTag, ParadigmSectionPatches> result = new LinkedHashMap<>();
        if (imperativeSection != null) {
            result.put(ImperativeTag.INSTANCE, imperativeSection.finish());
        }
        return result;
    }
}
