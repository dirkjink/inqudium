package eu.inqudium.config.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ImperativeSection}.
 *
 * <p>Accumulates a name-keyed map of {@link BulkheadPatch} instances as the user calls
 * {@code .bulkhead("name", ...)}. Builders for each call come from the
 * {@link ParadigmProvider#createBulkheadBuilder(String)} factory; the section casts each
 * returned builder to {@link ImperativeBulkheadBuilder} before handing it to the user's
 * configurer.
 */
public final class DefaultImperativeSection implements ImperativeSection {

    private final ParadigmProvider provider;
    private final Map<String, BulkheadPatch> bulkheadPatches = new LinkedHashMap<>();

    /**
     * @param provider the imperative paradigm provider supplying bulkhead builders.
     */
    public DefaultImperativeSection(ParadigmProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public ImperativeSection bulkhead(String name, Consumer<ImperativeBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createBulkheadBuilder(name);
        // The cast is safe by SPI contract: the imperative provider returns a builder that
        // implements ImperativeBulkheadBuilder.
        ImperativeBulkheadBuilder builder = (ImperativeBulkheadBuilder) base;
        configurer.accept(builder);
        bulkheadPatches.put(name, base.toPatch());
        return this;
    }

    /**
     * @return the accumulated patches, frozen into a {@link ParadigmSectionPatches}.
     */
    public ParadigmSectionPatches finish() {
        return new ParadigmSectionPatches(bulkheadPatches);
    }
}
