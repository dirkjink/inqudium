package eu.inqudium.config.live;

import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Atomic snapshot holder paired with a subscriber list.
 *
 * <p>Reads ({@link #snapshot()}) are a single volatile load — lock-free and safe under any
 * concurrency. Writes ({@link #apply(ComponentPatch)}) run a CAS retry loop: read the current
 * snapshot, build the new snapshot via the patch, compare-and-set. On a successful CAS every
 * subscriber is notified exactly once with the new snapshot.
 *
 * <p>Phase&nbsp;1 of the configuration refactor uses this container directly: hot patches go
 * through {@code apply(patch)} without veto. Phase&nbsp;2 routes hot patches through the veto
 * chain first and only invokes {@code apply} on full acceptance.
 *
 * @param <S> the component's snapshot type.
 */
public final class LiveContainer<S extends ComponentSnapshot> {

    private final AtomicReference<S> ref;
    private final CopyOnWriteArrayList<Consumer<S>> listeners;

    /**
     * @param initial the initial snapshot; must be non-null.
     */
    public LiveContainer(S initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial, "initial snapshot"));
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * @return the current snapshot. Lock-free, returns a stable value reachable from at least one
     *         happens-before edge with the most recent successful {@link #apply(ComponentPatch)}.
     */
    public S snapshot() {
        return ref.get();
    }

    /**
     * Apply a patch atomically.
     *
     * <p>The patch is applied to the current snapshot, the result CAS'd into place, and every
     * subscriber notified once. On CAS contention, the patch is re-applied to the new current
     * snapshot — the loop only exits with a fully-applied snapshot reflecting the patch's intent
     * relative to the snapshot it raced against.
     *
     * @param patch the patch to apply; must be non-null.
     * @return the snapshot resulting from the successful CAS.
     */
    public S apply(ComponentPatch<S> patch) {
        Objects.requireNonNull(patch, "patch");
        S prev;
        S next;
        do {
            prev = ref.get();
            next = patch.applyTo(prev);
        } while (!ref.compareAndSet(prev, next));
        for (Consumer<S> listener : listeners) {
            listener.accept(next);
        }
        return next;
    }

    /**
     * Register a subscriber that observes every successful snapshot replacement.
     *
     * <p>Subscribers receive the new snapshot exactly once per successful CAS, in registration
     * order. The returned handle removes the subscriber when closed; a closed handle is a no-op
     * on subsequent closes.
     *
     * @param listener the subscriber; must be non-null.
     * @return an {@link AutoCloseable} that unregisters the subscriber on {@code close()}.
     */
    public AutoCloseable subscribe(Consumer<S> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
