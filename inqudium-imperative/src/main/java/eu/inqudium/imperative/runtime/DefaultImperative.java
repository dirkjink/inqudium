package eu.inqudium.imperative.runtime;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.Imperative;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
     */
    public record Entry(InqBulkhead bulkhead, LiveContainer<BulkheadSnapshot> live) {
        public Entry {
            Objects.requireNonNull(bulkhead, "bulkhead");
            Objects.requireNonNull(live, "live");
        }
    }

    private final ImperativeProvider provider;
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
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(initialBulkheads, "initialBulkheads");
        this.bulkheads = new AtomicReference<>(
                Map.copyOf(new LinkedHashMap<>(initialBulkheads)));
    }

    @Override
    public ParadigmTag paradigm() {
        return ImperativeTag.INSTANCE;
    }

    @Override
    public ImperativeBulkhead bulkhead(String name) {
        Map<String, Entry> snapshot = bulkheads.get();
        Entry e = snapshot.get(name);
        if (e == null) {
            throw new IllegalArgumentException(
                    "No bulkhead named '" + name + "'. Available: " + snapshot.keySet());
        }
        return e.bulkhead();
    }

    @Override
    public Optional<ImperativeBulkhead> findBulkhead(String name) {
        Entry e = bulkheads.get().get(name);
        return e != null ? Optional.of(e.bulkhead()) : Optional.empty();
    }

    @Override
    public Set<String> bulkheadNames() {
        return Set.copyOf(bulkheads.get().keySet());
    }

    @Override
    public Map<String, ApplyOutcome> applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(patches, "patches");
        Map<String, ApplyOutcome> outcomes = new LinkedHashMap<>();
        updateLock.lock();
        try {
            Map<String, Entry> current = bulkheads.get();
            Map<String, Entry> next = new LinkedHashMap<>(current);
            for (Map.Entry<String, BulkheadPatch> e : patches.bulkheadPatches().entrySet()) {
                String name = e.getKey();
                BulkheadPatch patch = e.getValue();
                Entry existing = next.get(name);
                if (existing != null) {
                    BulkheadSnapshot before = existing.live().snapshot();
                    BulkheadSnapshot after = existing.live().apply(patch);
                    outcomes.put(name,
                            before.equals(after) ? ApplyOutcome.UNCHANGED : ApplyOutcome.PATCHED);
                } else {
                    Entry created = provider.materializeBulkhead(general, name, patch);
                    next.put(name, created);
                    outcomes.put(name, ApplyOutcome.ADDED);
                }
            }
            bulkheads.set(Map.copyOf(next));
        } finally {
            updateLock.unlock();
        }
        return outcomes;
    }
}
