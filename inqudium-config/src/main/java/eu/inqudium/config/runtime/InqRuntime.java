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
 * after build go through {@link #update} and (when format adapters land) {@code apply}; the
 * read-only {@link #dryRun} and {@link #diagnose} entry points serve validation and
 * cross-component diagnostics without mutating the runtime.
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
     * <p>Supports {@code add} (a {@code .bulkhead(name, ...)} call against an unknown name),
     * {@code patch} (the same call against a known name — only the touched fields change), and
     * {@code remove} (a {@code .removeBulkhead(name)} call). Hot patches go through the
     * listener veto chain and the component-internal mutability check; structural removals go
     * through the same chain via {@code decideRemoval}/{@code evaluateRemoval}.
     *
     * <p>A direct-patch entry point for format adapters (YAML, JSON, ...) is not part of this
     * surface. Format adapters that want to emit batched patches across paradigms route
     * through {@link #update} too, by reusing the DSL builders.
     *
     * @param updater configures the update via the same DSL used at initial-config time.
     * @return a report describing per-component outcomes, validation findings, and any veto
     *         findings raised along the way.
     */
    BuildReport update(Consumer<InqudiumUpdateBuilder> updater);

    /**
     * Validate an update without applying it.
     *
     * <p>Runs the same DSL traversal, the same hot-state veto chain, and the same component-
     * internal mutability check as {@link #update}, but produces no side effects: no live
     * snapshot is replaced, no component is materialized or shut down, no topology event
     * ({@code RuntimeComponentAddedEvent}, {@code RuntimeComponentPatchedEvent},
     * {@code RuntimeComponentRemovedEvent}, {@code RuntimeComponentVetoedEvent}) is published.
     * The returned {@link BuildReport} carries the per-component outcomes and veto findings the
     * matching {@code update} call would have produced for the runtime's current state.
     *
     * <p>The intended consumer is a CI/CD pipeline that wants to validate a planned configuration
     * change against a running system before issuing the real {@link #update} call. Two
     * consecutive {@code dryRun(...)} invocations on an unchanged runtime are idempotent and
     * yield identical outcomes.
     *
     * <p>Class-1 (DSL setter) and class-2 (snapshot compact constructor) violations propagate
     * as exceptions exactly as they would on the corresponding {@link #update} call — dry-run
     * does not turn invalid input into a graceful finding.
     *
     * @param updater configures the prospective update via the same DSL as {@link #update}.
     * @return the report the update would have produced.
     */
    BuildReport dryRun(Consumer<InqudiumUpdateBuilder> updater);

    /**
     * Run every registered class-4 {@link eu.inqudium.config.validation.CrossComponentRule
     * CrossComponentRule} against the runtime's current
     * {@link eu.inqudium.config.runtime.InqConfigView InqConfigView} and return the collected
     * findings.
     *
     * <p>Diagnose is read-only: it inspects snapshots and emits findings, never mutates a
     * component, never publishes a topology event. Operators run it on demand to ask "is the
     * system configured sanely?" — the runtime never invokes it on its own.
     *
     * <p>Rules are discovered at build time via {@link java.util.ServiceLoader} and iterated in
     * the order the loader produces them. A rule that throws does not abort the diagnose call:
     * the framework catches the exception, emits a synthetic finding under rule id
     * {@code INQ-DIAGNOSE-RULE-FAILURE} carrying the offending rule's class and the exception
     * message, and continues with the remaining rules.
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
