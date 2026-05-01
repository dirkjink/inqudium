package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.log.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Paradigm-agnostic update dispatcher.
 *
 * <p>Per-paradigm containers (e.g. {@code DefaultImperative}) iterate over their incoming patches
 * and call this dispatcher once per existing component to obtain a {@link DispatchResult}. The
 * dispatcher's job is the cold-versus-hot routing decision specified in ADR-028:
 *
 * <ul>
 *   <li>{@link LifecycleState#COLD COLD}: apply the patch directly through {@link LiveContainer},
 *       then report {@link ApplyOutcome#PATCHED} or {@link ApplyOutcome#UNCHANGED} depending on
 *       whether the patch produced an effective snapshot change. The veto chain is bypassed —
 *       cold components are still in their pre-execute configuration phase, and registered
 *       listeners are not consulted even if any are present.</li>
 *   <li>{@link LifecycleState#HOT HOT}: route through the veto chain. Listeners are consulted
 *       in registration order; the chain is conjunctive — the first
 *       {@link ChangeDecision.Veto Veto} rejects the entire patch, no further listeners are
 *       called, and the dispatcher returns {@link DispatchResult#vetoed} with a
 *       {@link VetoFinding} of {@link VetoFinding.Source#LISTENER LISTENER} source. After full
 *       listener acceptance, the component-internal {@link InternalMutabilityCheck} runs as the
 *       last gate — its {@link ChangeDecision.Veto Veto} produces a
 *       {@link VetoFinding.Source#COMPONENT_INTERNAL COMPONENT_INTERNAL} finding instead. Only
 *       when every listener and the internal check accept does the patch reach the apply step,
 *       which is then identical to the cold path.</li>
 * </ul>
 *
 * <p>The dispatcher is stateless and thread-safe. A single instance can serve every paradigm
 * container in the runtime; concurrent {@code update(...)} calls are still serialized by each
 * container's own update lock.
 *
 * <p>The {@link ApplyOutcome#ADDED ADDED} outcome — assigned to freshly materialized components
 * — does not flow through the dispatcher: a brand-new component has no prior snapshot to compare
 * against, no listeners to consult, and is cold by definition. The container handles that case
 * directly when it materializes the new entry.
 */
public final class UpdateDispatcher {

    private static final String ROLE_LISTENER = "listener";
    private static final String ROLE_INTERNAL_CHECK = "internal mutability check";

    private final Logger logger;

    /**
     * No-arg constructor that produces a dispatcher whose absorbed-throw logging is silently
     * dropped. Suitable for unit tests that do not assert on log output; production wiring uses
     * {@link #UpdateDispatcher(LoggerFactory)} so absorbed listener and internal-check throws are
     * routed to the runtime's configured {@link LoggerFactory}.
     */
    public UpdateDispatcher() {
        this(LoggerFactory.NO_OP_LOGGER_FACTORY);
    }

    /**
     * @param loggerFactory the runtime's logger factory. Listener and internal-check throws that
     *                      the dispatcher absorbs as synthetic vetoes are logged through this
     *                      factory at error level so the operator can trace them to source.
     */
    public UpdateDispatcher(LoggerFactory loggerFactory) {
        Objects.requireNonNull(loggerFactory, "loggerFactory");
        this.logger = loggerFactory.getLogger(UpdateDispatcher.class);
    }

    /**
     * Apply a patch to an existing component, routing through the cold or hot path.
     *
     * <p>The {@code target} type expresses the dispatcher's contract on the handle: it must
     * support lifecycle inspection ({@link LifecycleAware}), listener iteration
     * ({@link ListenerRegistry}), and component-internal mutability evaluation
     * ({@link InternalMutabilityCheck}). The per-paradigm component handles
     * ({@code InqBulkhead}, future reactive/Kotlin/RxJava analogues) implement all three already.
     *
     * @param key    the component's lookup key. Used in the {@link VetoFinding} when a listener
     *               or the component-internal check vetoes the patch.
     * @param target the existing component handle.
     * @param live   the live container backing the component.
     * @param patch  the patch to apply.
     * @param <S>    the component's snapshot type.
     * @param <T>    the handle type — must combine {@link LifecycleAware},
     *               {@link ListenerRegistry}{@code <S>}, and
     *               {@link InternalMutabilityCheck}{@code <S>}.
     * @return the dispatch result. Carries {@link ApplyOutcome#PATCHED PATCHED}/
     *         {@link ApplyOutcome#UNCHANGED UNCHANGED} on accept,
     *         {@link ApplyOutcome#VETOED VETOED} with a {@link VetoFinding} on veto.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public <S extends ComponentSnapshot,
            T extends LifecycleAware & ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult dispatch(
            ComponentKey key,
            T target,
            LiveContainer<S> live,
            ComponentPatch<S> patch) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(patch, "patch");

        return switch (target.lifecycleState()) {
            case COLD -> DispatchResult.applied(applyCold(live, patch));
            case HOT -> dispatchHot(key, target, live, patch);
        };
    }

    /**
     * Run the same veto chain as {@link #dispatch} but report the verdict that <em>would</em>
     * apply, without mutating the live container. The decide path drives
     * {@code runtime.dryRun(...)}: subscribers and the BuildReport see the same outcomes the
     * companion {@link #dispatch} call would have produced.
     *
     * <p>For a cold target the listener chain and the internal check are bypassed (matching
     * dispatch); the result is computed by feeding the patch through the snapshot's
     * {@link ComponentPatch#applyTo} and comparing with the current snapshot. The transient
     * snapshot is discarded immediately, but its compact constructor still runs — class-2
     * invariant violations therefore surface here as exceptions just like they would on a real
     * apply, keeping dry-run behaviour aligned with update behaviour.
     *
     * <p>For a hot target the listener chain runs in registration order, the internal check
     * runs as the last gate, and on full acceptance the cold-style decision rule above produces
     * the {@link ApplyOutcome#PATCHED PATCHED}/{@link ApplyOutcome#UNCHANGED UNCHANGED} outcome.
     * On any veto the result is {@link DispatchResult#vetoed(VetoFinding)} with the finding the
     * real dispatch would also have produced — this is the core promise of {@code dryRun}.
     *
     * @param key    the component's lookup key.
     * @param target the existing component handle.
     * @param live   the live container backing the component, read once.
     * @param patch  the patch whose hypothetical effect is being evaluated.
     * @param <S>    the component's snapshot type.
     * @param <T>    the handle type — must combine {@link LifecycleAware},
     *               {@link ListenerRegistry}{@code <S>}, and
     *               {@link InternalMutabilityCheck}{@code <S>}.
     * @return the dispatch result that {@link #dispatch} would have produced for this
     *         {@code (target, patch)} pair.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public <S extends ComponentSnapshot,
            T extends LifecycleAware & ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult decide(
            ComponentKey key,
            T target,
            LiveContainer<S> live,
            ComponentPatch<S> patch) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(patch, "patch");

        return switch (target.lifecycleState()) {
            case COLD -> DispatchResult.applied(decideCold(live, patch));
            case HOT -> decideHot(key, target, live, patch);
        };
    }

    private static <S extends ComponentSnapshot> ApplyOutcome applyCold(
            LiveContainer<S> live, ComponentPatch<S> patch) {
        S before = live.snapshot();
        S after = live.apply(patch);
        return before.equals(after) ? ApplyOutcome.UNCHANGED : ApplyOutcome.PATCHED;
    }

    private static <S extends ComponentSnapshot> ApplyOutcome decideCold(
            LiveContainer<S> live, ComponentPatch<S> patch) {
        S before = live.snapshot();
        S after = patch.applyTo(before);
        return before.equals(after) ? ApplyOutcome.UNCHANGED : ApplyOutcome.PATCHED;
    }

    /**
     * Route a structural removal through the veto chain (ADR-026).
     *
     * <p>Listeners are consulted via {@link ChangeRequestListener#decideRemoval(ComponentSnapshot)},
     * the component-internal check via
     * {@link InternalMutabilityCheck#evaluateRemoval(ComponentSnapshot)}. Both methods default to
     * accept; listeners and components opt in to vetoing removals explicitly. Conjunctive
     * semantics match the patch path — the first {@link ChangeDecision.Veto Veto} wins, the
     * internal check is consulted only after every listener has accepted, and the dispatcher
     * never short-circuits the iteration on accept.
     *
     * <p>The dispatcher does not itself remove the component — its return signals the
     * container's next move:
     *
     * <ul>
     *   <li>{@link ApplyOutcome#REMOVED REMOVED}: the container shuts the hot phase down and
     *       drops the component from its paradigm map.</li>
     *   <li>{@link ApplyOutcome#VETOED VETOED}: the container leaves the component in place; the
     *       attached {@link VetoFinding} carries the source and reason.</li>
     * </ul>
     *
     * <p>Removal of a {@link LifecycleState#COLD COLD} component bypasses the chain too and
     * always returns {@link ApplyOutcome#REMOVED REMOVED} — cold updates apply directly per
     * ADR-028, and structural removal of a never-executed component poses no in-flight risk.
     *
     * @param key    the component's lookup key. Used in the {@link VetoFinding} on veto.
     * @param target the existing component handle.
     * @param live   the live container backing the component, read once at dispatch time to
     *               supply the snapshot to listeners and to the internal check.
     * @param <S>    the component's snapshot type.
     * @param <T>    the handle type — must combine {@link LifecycleAware},
     *               {@link ListenerRegistry}{@code <S>}, and
     *               {@link InternalMutabilityCheck}{@code <S>}.
     * @return the dispatch result: {@link ApplyOutcome#REMOVED REMOVED} on accept,
     *         {@link ApplyOutcome#VETOED VETOED} with finding on veto.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public <S extends ComponentSnapshot,
            T extends LifecycleAware & ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult dispatchRemoval(
            ComponentKey key,
            T target,
            LiveContainer<S> live) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(live, "live");

        return switch (target.lifecycleState()) {
            case COLD -> DispatchResult.applied(ApplyOutcome.REMOVED);
            case HOT -> dispatchRemovalHot(key, target, live);
        };
    }

    private <S extends ComponentSnapshot,
            T extends ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult dispatchRemovalHot(
            ComponentKey key,
            T target,
            LiveContainer<S> live) {

        S currentSnapshot = live.snapshot();

        for (ChangeRequestListener<S> listener : target.listeners()) {
            ChangeDecision decision;
            try {
                decision = listener.decideRemoval(currentSnapshot);
            } catch (Exception ex) {
                logAbsorbedThrow(ROLE_LISTENER, key, ex);
                return DispatchResult.vetoed(new VetoFinding(
                        key, java.util.Set.of(),
                        synthesizeReason(ROLE_LISTENER, ex),
                        VetoFinding.Source.LISTENER));
            }
            if (decision instanceof ChangeDecision.Veto veto) {
                return DispatchResult.vetoed(new VetoFinding(
                        key, java.util.Set.of(), veto.reason(),
                        VetoFinding.Source.LISTENER));
            }
        }

        ChangeDecision internal;
        try {
            internal = target.evaluateRemoval(currentSnapshot);
        } catch (Exception ex) {
            logAbsorbedThrow(ROLE_INTERNAL_CHECK, key, ex);
            return DispatchResult.vetoed(new VetoFinding(
                    key, java.util.Set.of(),
                    synthesizeReason(ROLE_INTERNAL_CHECK, ex),
                    VetoFinding.Source.COMPONENT_INTERNAL));
        }
        if (internal instanceof ChangeDecision.Veto veto) {
            return DispatchResult.vetoed(new VetoFinding(
                    key, java.util.Set.of(), veto.reason(),
                    VetoFinding.Source.COMPONENT_INTERNAL));
        }

        return DispatchResult.applied(ApplyOutcome.REMOVED);
    }

    private <S extends ComponentSnapshot,
            T extends ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult dispatchHot(
            ComponentKey key,
            T target,
            LiveContainer<S> live,
            ComponentPatch<S> patch) {
        Optional<VetoFinding> veto = runHotVetoChain(key, target, live, patch);
        if (veto.isPresent()) {
            return DispatchResult.vetoed(veto.get());
        }
        return DispatchResult.applied(applyCold(live, patch));
    }

    private <S extends ComponentSnapshot,
            T extends ListenerRegistry<S> & InternalMutabilityCheck<S>>
    DispatchResult decideHot(
            ComponentKey key,
            T target,
            LiveContainer<S> live,
            ComponentPatch<S> patch) {
        Optional<VetoFinding> veto = runHotVetoChain(key, target, live, patch);
        if (veto.isPresent()) {
            return DispatchResult.vetoed(veto.get());
        }
        return DispatchResult.applied(decideCold(live, patch));
    }

    private <S extends ComponentSnapshot,
            T extends ListenerRegistry<S> & InternalMutabilityCheck<S>>
    Optional<VetoFinding> runHotVetoChain(
            ComponentKey key,
            T target,
            LiveContainer<S> live,
            ComponentPatch<S> patch) {

        // Build the request once and hand the same object to every listener and to the
        // component-internal check. The request is immutable so callers cannot mutate it for
        // one another. The post-patch snapshot is computed eagerly here so listeners and the
        // mutability check see a stable view — ADR-028 / ADR-032 requires evaluation against
        // the post-patch state.
        S currentSnapshot = live.snapshot();
        S postPatch = patch.applyTo(currentSnapshot);
        ChangeRequest<S> request = new DefaultChangeRequest<>(
                currentSnapshot, postPatch, patch.touchedFields(), patch.proposedValues());

        for (ChangeRequestListener<S> listener : target.listeners()) {
            ChangeDecision decision;
            try {
                decision = listener.decide(request);
            } catch (Exception ex) {
                // A listener that throws (instead of returning a clean Veto) is absorbed as a
                // synthetic veto for this component: the patch is rejected, the rest of the
                // listener loop is short-circuited (matches real-veto semantics), and the
                // throwable is logged at error level so the operator can trace it to source.
                // Other components in the same update wave are unaffected — this preserves the
                // cross-component-atomicity contract from ADR-026. Errors propagate untouched
                // so JVM-level conditions (OOM, StackOverflow, …) are not masked.
                logAbsorbedThrow(ROLE_LISTENER, key, ex);
                return Optional.of(new VetoFinding(
                        key, patch.touchedFields(),
                        synthesizeReason(ROLE_LISTENER, ex),
                        VetoFinding.Source.LISTENER));
            }
            if (decision instanceof ChangeDecision.Veto veto) {
                return Optional.of(new VetoFinding(
                        key, patch.touchedFields(), veto.reason(),
                        VetoFinding.Source.LISTENER));
            }
        }

        // Component-internal mutability check is the last gate before apply — runs only after
        // every listener has accepted, and therefore is never consulted on a listener-vetoed
        // patch.
        ChangeDecision internal;
        try {
            internal = target.evaluate(request);
        } catch (Exception ex) {
            logAbsorbedThrow(ROLE_INTERNAL_CHECK, key, ex);
            return Optional.of(new VetoFinding(
                    key, patch.touchedFields(),
                    synthesizeReason(ROLE_INTERNAL_CHECK, ex),
                    VetoFinding.Source.COMPONENT_INTERNAL));
        }
        if (internal instanceof ChangeDecision.Veto veto) {
            return Optional.of(new VetoFinding(
                    key, patch.touchedFields(), veto.reason(),
                    VetoFinding.Source.COMPONENT_INTERNAL));
        }

        return Optional.empty();
    }

    private void logAbsorbedThrow(String role, ComponentKey key, Exception ex) {
        // Four-arg form routes to LogAction#log(String, Object...) — the abstraction has fixed-
        // arity overloads only up to three args, so four args resolve to varargs. The trailing
        // Throwable in a varargs call is recognized by SLF4J's contract and logged with full
        // stack trace (in the no-op logger this whole call is dropped).
        logger.error().log(
                "Update {} threw on component {}: {}; absorbed as synthetic veto",
                role, key, summarize(ex), ex);
    }

    private static String synthesizeReason(String role, Exception ex) {
        String msg = ex.getMessage();
        return role + " threw " + ex.getClass().getSimpleName()
                + ": " + (msg != null ? msg : "no message");
    }

    private static String summarize(Exception ex) {
        String msg = ex.getMessage();
        return ex.getClass().getSimpleName() + ": " + (msg != null ? msg : "no message");
    }
}
