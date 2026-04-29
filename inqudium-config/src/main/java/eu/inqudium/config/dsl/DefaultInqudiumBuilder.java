package eu.inqudium.config.dsl;

import eu.inqudium.config.ConfigurationException;
import eu.inqudium.config.runtime.DefaultInqRuntime;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.runtime.ParadigmUnavailableException;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.ConsistencyRule;
import eu.inqudium.config.validation.ConsistencyRulePipeline;
import eu.inqudium.config.validation.CrossComponentRule;
import eu.inqudium.config.validation.ValidationFinding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    /**
     * Load every {@link CrossComponentRule} discoverable via {@link ServiceLoader} on the runtime
     * classpath. Iteration order is the {@code ServiceLoader} default — declaration order in each
     * {@code META-INF/services/eu.inqudium.config.validation.CrossComponentRule} file, in the
     * order the loader walks the classpath. Diagnose iterates the rules in this same order so the
     * resulting findings list is deterministic for a given classpath.
     */
    private static List<CrossComponentRule> loadCrossComponentRules() {
        List<CrossComponentRule> result = new ArrayList<>();
        for (CrossComponentRule rule : ServiceLoader.load(CrossComponentRule.class)) {
            result.add(rule);
        }
        return result;
    }

    /**
     * Load every class-3 {@link ConsistencyRule} discoverable via {@link ServiceLoader}. Same
     * iteration-order discipline as {@link #loadCrossComponentRules}: deterministic within a
     * classpath, so build reports remain reproducible. Application code adds its own rules by
     * shipping a {@code META-INF/services/eu.inqudium.config.validation.ConsistencyRule} entry —
     * the framework no longer hardcodes the rule list.
     */
    private static List<ConsistencyRule<?>> loadConsistencyRules() {
        List<ConsistencyRule<?>> result = new ArrayList<>();
        for (ConsistencyRule<?> rule : ServiceLoader.load(ConsistencyRule.class)) {
            result.add(rule);
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

        // Class-3 validation: run every registered consistency rule against every materialized
        // snapshot. Class-1 (DSL setters) and class-2 (snapshot compact constructors) have
        // already fired by the time we get here; reaching this point means every snapshot is
        // internally well-formed. Strict mode elevates warnings to errors before the
        // success/failure decision so a configuration that would have produced only warnings in
        // lenient mode aborts in strict mode.
        Stream<? extends ComponentSnapshot> snapshots = containers.values().stream()
                .flatMap(ParadigmContainer::snapshots);
        List<ValidationFinding> findings =
                ConsistencyRulePipeline.apply(snapshots, loadConsistencyRules());
        if (strict) {
            findings = ConsistencyRulePipeline.elevateWarningsToErrors(findings);
        }
        BuildReport report = new BuildReport(
                Instant.now(), findings, List.of(), Map.of());
        if (!report.isSuccess()) {
            throw new ConfigurationException(report);
        }

        return new DefaultInqRuntime(
                general, containers, providers, report, loadCrossComponentRules());
    }

    /**
     * @return whether {@link #strict()} was called. Exposed for tests and phase-2 wiring.
     */
    public boolean isStrict() {
        return strict;
    }
}
