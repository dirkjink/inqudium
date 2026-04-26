package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.DefaultInqRuntime;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.runtime.ParadigmUnavailableException;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqudiumBuilder}.
 *
 * <p>Accumulates the user's {@code .general(...)} configuration plus per-paradigm sections, then
 * runs the build pipeline in {@link #build()}: load paradigm providers via
 * {@link ServiceLoader}, materialize each declared section through its provider, and assemble
 * the {@link DefaultInqRuntime}. If a paradigm section is declared but no provider is on the
 * classpath, build raises {@link ParadigmUnavailableException} naming the missing module.
 *
 * <p>Phase&nbsp;1 covers only the imperative section. Reactive, RxJava&nbsp;3, and coroutine
 * paradigm entry points join the {@link InqudiumBuilder} interface as their providers come
 * online.
 */
public final class DefaultInqudiumBuilder implements InqudiumBuilder {

    private final GeneralSnapshotBuilder generalBuilder = new GeneralSnapshotBuilder();
    private final Map<ParadigmTag, ParadigmProvider> providers;
    private DefaultImperativeSection imperativeSection;
    private boolean strict;

    public DefaultInqudiumBuilder() {
        this.providers = loadProviders();
    }

    private static Map<ParadigmTag, ParadigmProvider> loadProviders() {
        Map<ParadigmTag, ParadigmProvider> result = new LinkedHashMap<>();
        for (ParadigmProvider provider : ServiceLoader.load(ParadigmProvider.class)) {
            result.put(provider.paradigm(), provider);
        }
        return result;
    }

    @Override
    public InqudiumBuilder general(Consumer<GeneralSnapshotBuilder> configurer) {
        configurer.accept(generalBuilder);
        return this;
    }

    @Override
    public InqudiumBuilder imperative(Consumer<ImperativeSection> configurer) {
        ParadigmProvider provider = providers.get(ImperativeTag.INSTANCE);
        if (provider == null) {
            throw new ParadigmUnavailableException(
                    "The 'imperative' paradigm requires module 'inqudium-imperative' on the "
                            + "classpath. Add it as a dependency or remove .imperative(...) "
                            + "sections from your configuration.");
        }
        if (imperativeSection == null) {
            imperativeSection = new DefaultImperativeSection(provider);
        }
        configurer.accept(imperativeSection);
        return this;
    }

    @Override
    public InqudiumBuilder strict() {
        // TODO(refactor 1.8): wire strict mode into the consistency-rule pipeline so warnings
        //   elevate to errors during build.
        this.strict = true;
        return this;
    }

    @Override
    public InqRuntime build() {
        GeneralSnapshot general = generalBuilder.build();

        Map<ParadigmTag, ParadigmContainer<?>> containers = new LinkedHashMap<>();

        // Imperative paradigm: materialize from declared section, or supply an empty container
        // when the provider is on the classpath but no .imperative(...) was declared. Per
        // ADR-026, "an empty paradigm is a normal state".
        ParadigmProvider imperativeProvider = providers.get(ImperativeTag.INSTANCE);
        if (imperativeProvider != null) {
            ParadigmSectionPatches patches = imperativeSection != null
                    ? imperativeSection.finish()
                    : new ParadigmSectionPatches(Map.of());
            containers.put(
                    ImperativeTag.INSTANCE,
                    imperativeProvider.createContainer(general, patches));
        }

        return new DefaultInqRuntime(general, containers, providers);
    }

    /**
     * @return whether {@link #strict()} was called. Exposed for phase&nbsp;1.8 wiring.
     */
    public boolean isStrict() {
        return strict;
    }
}
