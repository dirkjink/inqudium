package eu.inqudium.config.runtime;

import eu.inqudium.config.dsl.InqudiumUpdateBuilder;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.DiagnosisReport;

import java.util.function.Consumer;

/**
 * The runtime-level container — single source of truth for configured components and the
 * lifecycle root for the entire framework instance.
 *
 * <p>Built via {@code Inqudium.configure().build()}; closed via {@link #close()}. Holds the
 * general snapshot, the per-paradigm containers, and the cross-paradigm read view. Mutations
 * after build go through {@link #update}, {@link #apply}, or — once the diagnostic and dry-run
 * features are wired up in phase&nbsp;2 — {@link #dryRun} and {@link #diagnose}.
 *
 * <p>The runtime is a single instance per application by convention. Multiple runtimes are
 * technically possible (for tests or multi-tenant scenarios), but the framework does not
 * provide a default singleton — callers manage the lifecycle themselves.
 */
public interface InqRuntime extends AutoCloseable {

    /**
     * @return the general snapshot configured at build time.
     */
    GeneralSnapshot general();

    /**
     * @return the cross-paradigm read view onto the configured components.
     */
    InqConfigView config();

    /**
     * @return the imperative paradigm container.
     * @throws ParadigmUnavailableException if no imperative provider is on the classpath.
     */
    Imperative imperative();

    /**
     * @return the {@link BuildReport} produced at the runtime's <em>initial</em> build time.
     *
     *         <p>Carries every class-2 and class-3 validation finding raised during
     *         construction plus the build timestamp. Successful builds still produce a report
     *         — it just has no findings and reports {@link BuildReport#isSuccess() isSuccess()
     *         == true}. Failed builds do not produce a runtime; the report travels in
     *         {@link eu.inqudium.config.ConfigurationException#report
     *         ConfigurationException.report()} instead.
     *
     *         <p><strong>The returned reference is stable for the runtime's lifetime.</strong>
     *         Subsequent {@link #update update} calls return their own fresh
     *         {@code BuildReport} via the method's return value, but they do <em>not</em>
     *         overwrite the build report exposed here. Two reasons:
     *         <ul>
     *           <li>The "initial build" is a distinct event with a stable historical value —
     *               overwriting it would lose what the configuration was at startup, which is
     *               often the answer support engineers need first.</li>
     *           <li>No mutation, no race conditions: callers can read this accessor from any
     *               thread without coordinating with concurrent updates.</li>
     *         </ul>
     *
     *         <p>Callers wishing to observe per-update reports must capture them from
     *         {@code runtime.update(...)} themselves. Phase&nbsp;2 may introduce a
     *         {@code lastUpdateReport()} or similar accessor for the "latest mutation" case;
     *         until then, the update-side report is only visible at the call site.
     */
    BuildReport lastBuildReport();

    /**
     * Apply a DSL-driven update against the running runtime.
     *
     * <p>Phase&nbsp;1 supports {@code add} (a {@code .bulkhead(name, ...)} call against an
     * unknown name) and {@code patch} (the same call against a known name — only the touched
     * fields change). Structural removal and dry-run are deferred to phase&nbsp;2.
     *
     * <p>The direct-patch entry point used by format adapters (YAML, JSON, ...) is intentionally
     * absent in phase&nbsp;1. It needs a paradigm/component-type discriminator on each patch to
     * route correctly across paradigms; that signature lands together with the format adapters
     * in phase&nbsp;2 (likely as {@code apply(Map<ParadigmTag, List<ComponentPatch<?>>>)}).
     * Until then, callers route through {@link #update}.
     *
     * @param updater configures the update via the same DSL used at initial-config time.
     * @return a report describing per-component outcomes, validation findings, and any
     *         phase-2 vetoes.
     */
    BuildReport update(Consumer<InqudiumUpdateBuilder> updater);

    /**
     * Validate an update without applying it. Stub in phase&nbsp;1; fully wired up in phase&nbsp;2
     * alongside the veto chain.
     *
     * @param updater configures the prospective update via the same DSL as {@link #update}.
     * @return the report the update would have produced.
     */
    BuildReport dryRun(Consumer<InqudiumUpdateBuilder> updater);

    /**
     * Run all registered cross-component diagnostic rules and report findings. Stub in
     * phase&nbsp;1; fully wired up in phase&nbsp;2 alongside the {@code CrossComponentRule} SPI.
     *
     * @return a report listing every diagnostic finding produced by the registered rules.
     */
    DiagnosisReport diagnose();

    /**
     * Shut down every paradigm container, render every handle inert, and release the runtime's
     * own resources (notably the runtime-scoped event publisher). Idempotent — calling close
     * multiple times has no effect after the first call. Subsequent operations on this runtime
     * throw {@link IllegalStateException}.
     */
    @Override
    void close();
}
