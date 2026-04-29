package eu.inqudium.imperative.runtime;

import eu.inqudium.config.event.RuntimeComponentAddedEvent;
import eu.inqudium.config.event.RuntimeComponentPatchedEvent;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.event.RuntimeComponentVetoedEvent;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.DispatchResult;
import eu.inqudium.config.runtime.Imperative;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.ParadigmApplyResult;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.runtime.UpdateDispatcher;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Default implementation of the {@link Imperative} paradigm container.
 *
 * <p>Holds an immutable name-keyed map of {@link Entry} instances. Each entry pairs the
 * {@link InqBulkhead} component with the {@link LiveContainer} backing it — the live container
 * is needed for runtime updates so a patch can be applied without going through the user-facing
 * handle API. Reads are lock-free against the {@link AtomicReference} that holds the current
 * map; writes (during {@link #applyUpdate}) acquire a {@link ReentrantLock} that serialises
 * concurrent update calls.
 *
 * <p>Holds a back-reference to {@link ImperativeProvider} so the container can materialize new
 * bulkheads through the same path that built the initial set when {@link #applyUpdate}
 * encounters a previously-unknown name.
 */
public final class DefaultImperative implements Imperative {

    /**
     * Pair of a live bulkhead component and its backing live container. Stored together so the
     * update path can apply a patch via {@link LiveContainer#apply} without exposing the live
     * container through the public handle API.
     *
     * <p>The bulkhead's type parameters are intentionally wildcard-typed: the runtime registry
     * serves a single component instance to callers of any call shape (ADR-033 Stage 2), so the
     * record must not commit to a concrete instantiation. Type-aware consumers cast at their
     * call site.
     */
    public record Entry(InqBulkhead<?, ?> bulkhead, LiveContainer<BulkheadSnapshot> live) {
        public Entry {
            Objects.requireNonNull(bulkhead, "bulkhead");
            Objects.requireNonNull(live, "live");
        }
    }

    private final ImperativeProvider provider;
    private final UpdateDispatcher dispatcher;
    private final AtomicReference<Map<String, Entry>> bulkheads;
    private final ReentrantLock updateLock = new ReentrantLock();

    /**
     * @param provider         the imperative paradigm provider; used to materialize new
     *                         bulkheads encountered during {@link #applyUpdate}.
     * @param initialBulkheads the set of bulkheads built at runtime construction time, in
     *                         registration order.
     */
    public DefaultImperative(
            ImperativeProvider provider,
            Map<String, Entry> initialBulkheads) {
        this(provider, initialBulkheads, new UpdateDispatcher());
    }

    /**
     * Test-friendly constructor that lets callers inject a specific {@link UpdateDispatcher}.
     * Production code uses the public single-argument-style constructor; test code uses this one
     * to substitute fake or instrumented dispatchers.
     *
     * @param provider         the imperative paradigm provider.
     * @param initialBulkheads the set of bulkheads built at runtime construction time.
     * @param dispatcher       the dispatcher used to route patches against existing components.
     */
    public DefaultImperative(
            ImperativeProvider provider,
            Map<String, Entry> initialBulkheads,
            UpdateDispatcher dispatcher) {
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(initialBulkheads, "initialBulkheads");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.bulkheads = new AtomicReference<>(
                Map.copyOf(new LinkedHashMap<>(initialBulkheads)));
    }

    @Override
    public ParadigmTag paradigm() {
        return ImperativeTag.INSTANCE;
    }

    @Override
    public BulkheadHandle<ImperativeTag> bulkhead(String name) {
        Map<String, Entry> snapshot = bulkheads.get();
        Entry e = snapshot.get(name);
        if (e == null) {
            throw new IllegalArgumentException(
                    "No bulkhead named '" + name + "'. Available: " + snapshot.keySet());
        }
        return e.bulkhead();
    }

    @Override
    public Optional<BulkheadHandle<ImperativeTag>> findBulkhead(String name) {
        Entry e = bulkheads.get().get(name);
        return e != null ? Optional.of(e.bulkhead()) : Optional.empty();
    }

    @Override
    public Set<String> bulkheadNames() {
        return Set.copyOf(bulkheads.get().keySet());
    }

    @Override
    public ParadigmApplyResult applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(patches, "patches");
        Map<ComponentKey, ApplyOutcome> outcomes = new LinkedHashMap<>();
        List<VetoFinding> vetoFindings = new ArrayList<>();
        updateLock.lock();
        try {
            Map<String, Entry> current = bulkheads.get();
            Map<String, Entry> next = new LinkedHashMap<>(current);
            for (Map.Entry<String, BulkheadPatch> e : patches.bulkheadPatches().entrySet()) {
                String name = e.getKey();
                BulkheadPatch patch = e.getValue();
                ComponentKey key = new ComponentKey(name, ImperativeTag.INSTANCE);
                Entry existing = next.get(name);
                if (existing != null) {
                    DispatchResult result = dispatcher.dispatch(
                            key, existing.bulkhead(), existing.live(), patch);
                    outcomes.put(key, result.outcome());
                    result.vetoFinding().ifPresent(vetoFindings::add);
                    if (result.outcome() == ApplyOutcome.PATCHED) {
                        general.eventPublisher().publish(new RuntimeComponentPatchedEvent(
                                name, InqElementType.BULKHEAD, patch.touchedFields(),
                                general.clock().instant()));
                    } else if (result.outcome() == ApplyOutcome.VETOED) {
                        general.eventPublisher().publish(new RuntimeComponentVetoedEvent(
                                name, InqElementType.BULKHEAD,
                                result.vetoFinding().orElseThrow(),
                                general.clock().instant()));
                    }
                } else {
                    Entry created = provider.materializeBulkhead(general, name, patch);
                    next.put(name, created);
                    outcomes.put(key, ApplyOutcome.ADDED);
                    general.eventPublisher().publish(new RuntimeComponentAddedEvent(
                            name, InqElementType.BULKHEAD, general.clock().instant()));
                }
            }
            for (String name : patches.bulkheadRemovals()) {
                ComponentKey key = new ComponentKey(name, ImperativeTag.INSTANCE);
                Entry existing = next.get(name);
                if (existing == null) {
                    // Removing a name that does not exist is semantically a no-op — the runtime
                    // is already in the requested state. UNCHANGED matches the convention used
                    // for no-op patches.
                    outcomes.put(key, ApplyOutcome.UNCHANGED);
                    continue;
                }
                DispatchResult result = dispatcher.dispatchRemoval(
                        key, existing.bulkhead(), existing.live());
                outcomes.put(key, result.outcome());
                result.vetoFinding().ifPresent(vetoFindings::add);
                if (result.outcome() == ApplyOutcome.REMOVED) {
                    existing.bulkhead().markRemoved();
                    next.remove(name);
                    general.eventPublisher().publish(new RuntimeComponentRemovedEvent(
                            name, InqElementType.BULKHEAD, general.clock().instant()));
                } else if (result.outcome() == ApplyOutcome.VETOED) {
                    general.eventPublisher().publish(new RuntimeComponentVetoedEvent(
                            name, InqElementType.BULKHEAD,
                            result.vetoFinding().orElseThrow(),
                            general.clock().instant()));
                }
            }
            bulkheads.set(Map.copyOf(next));
        } finally {
            updateLock.unlock();
        }
        return new ParadigmApplyResult(outcomes, vetoFindings);
    }

    @Override
    public ParadigmApplyResult dryRunUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(patches, "patches");
        Map<ComponentKey, ApplyOutcome> outcomes = new LinkedHashMap<>();
        List<VetoFinding> vetoFindings = new ArrayList<>();
        // Snapshot the map under the same lock applyUpdate uses, so dryRun observes a coherent
        // view even under concurrent updates. We never write back — the lock is released as soon
        // as we have an immutable handle on the current entries.
        Map<String, Entry> snapshot;
        updateLock.lock();
        try {
            snapshot = bulkheads.get();
        } finally {
            updateLock.unlock();
        }
        for (Map.Entry<String, BulkheadPatch> e : patches.bulkheadPatches().entrySet()) {
            String name = e.getKey();
            BulkheadPatch patch = e.getValue();
            ComponentKey key = new ComponentKey(name, ImperativeTag.INSTANCE);
            Entry existing = snapshot.get(name);
            if (existing != null) {
                DispatchResult result = dispatcher.decide(
                        key, existing.bulkhead(), existing.live(), patch);
                outcomes.put(key, result.outcome());
                result.vetoFinding().ifPresent(vetoFindings::add);
            } else {
                // Validate construction without registering the component. Throws on any class-2
                // invariant violation, matching the apply path's failure mode.
                provider.dryMaterializeBulkhead(name, patch);
                outcomes.put(key, ApplyOutcome.ADDED);
            }
        }
        for (String name : patches.bulkheadRemovals()) {
            ComponentKey key = new ComponentKey(name, ImperativeTag.INSTANCE);
            Entry existing = snapshot.get(name);
            if (existing == null) {
                outcomes.put(key, ApplyOutcome.UNCHANGED);
                continue;
            }
            // dispatchRemoval is decision-only on the dispatcher side already — the actual map
            // mutation happens in applyUpdate above. We can therefore reuse it directly.
            DispatchResult result = dispatcher.dispatchRemoval(
                    key, existing.bulkhead(), existing.live());
            outcomes.put(key, result.outcome());
            result.vetoFinding().ifPresent(vetoFindings::add);
        }
        return new ParadigmApplyResult(outcomes, vetoFindings);
    }

    @Override
    public Stream<? extends ComponentSnapshot> snapshots() {
        return bulkheads.get().values().stream().map(e -> e.live().snapshot());
    }
}
