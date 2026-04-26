package eu.inqudium.config.runtime;

import eu.inqudium.config.dsl.DefaultInqudiumUpdateBuilder;
import eu.inqudium.config.dsl.InqudiumUpdateBuilder;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.DiagnosisReport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Default implementation of {@link InqRuntime}.
 *
 * <p>Holds the {@link GeneralSnapshot}, the per-paradigm {@link ParadigmContainer} instances
 * produced at build time, and the cross-paradigm {@link InqConfigView}. Lifecycle is bounded by
 * a single {@link AtomicBoolean closed flag}: every accessor checks {@link #ensureOpen()} and
 * raises {@link IllegalStateException} after close. Closing is idempotent.
 *
 * <p>Cross-paradigm views iterate {@link ParadigmContainer#snapshots()} on every container
 * without branching on container type — adding a new paradigm in later phases is a pure
 * implementation task in the new container; this class does not need to be touched.
 */
public final class DefaultInqRuntime implements InqRuntime {

    private final GeneralSnapshot general;
    private final Map<ParadigmTag, ParadigmContainer<?>> containers;
    private final Map<ParadigmTag, ParadigmProvider> providers;
    private final InqConfigView configView;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DefaultInqRuntime(
            GeneralSnapshot general,
            Map<ParadigmTag, ParadigmContainer<?>> containers,
            Map<ParadigmTag, ParadigmProvider> providers) {
        this.general = Objects.requireNonNull(general, "general");
        this.containers = Map.copyOf(Objects.requireNonNull(containers, "containers"));
        this.providers = Map.copyOf(Objects.requireNonNull(providers, "providers"));
        this.configView = new DefaultInqConfigView(this);
    }

    @Override
    public GeneralSnapshot general() {
        ensureOpen();
        return general;
    }

    @Override
    public InqConfigView config() {
        ensureOpen();
        return configView;
    }

    @Override
    public Imperative imperative() {
        ensureOpen();
        ParadigmContainer<?> container = containers.get(ImperativeTag.INSTANCE);
        if (container == null) {
            throw new ParadigmUnavailableException(
                    "The 'imperative' paradigm requires module 'inqudium-imperative' on the "
                            + "classpath.");
        }
        return (Imperative) container;
    }

    @Override
    public BuildReport update(Consumer<InqudiumUpdateBuilder> updater) {
        ensureOpen();
        Objects.requireNonNull(updater, "updater");
        DefaultInqudiumUpdateBuilder builder = new DefaultInqudiumUpdateBuilder(providers);
        updater.accept(builder);
        Map<ComponentKey, ApplyOutcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<ParadigmTag, ParadigmSectionPatches> e
                : builder.toSectionPatches().entrySet()) {
            ParadigmContainer<?> container = containers.get(e.getKey());
            if (container == null) {
                // The update builder already raised ParadigmUnavailableException for missing
                // providers; reaching here without a container is a framework invariant
                // violation.
                throw new IllegalStateException(
                        "no container registered for paradigm " + e.getKey());
            }
            outcomes.putAll(container.applyUpdate(general, e.getValue()));
        }
        return new BuildReport(Instant.now(), List.of(), List.of(), outcomes);
    }

    @Override
    public BuildReport dryRun(Consumer<InqudiumUpdateBuilder> updater) {
        ensureOpen();
        // Phase 2 wires this up alongside the veto chain.
        return new BuildReport(Instant.now(), List.of(), List.of(), Map.of());
    }

    @Override
    public DiagnosisReport diagnose() {
        ensureOpen();
        // Phase 2 wires this up alongside the CrossComponentRule SPI.
        return new DiagnosisReport(Instant.now(), List.of());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                general.eventPublisher().close();
            } catch (Exception ignored) {
                // best-effort close; failures here should not propagate to the caller.
            }
        }
    }

    /**
     * @return a stream over every component snapshot across every paradigm. Iterates each
     *         container's {@link ParadigmContainer#snapshots()} in declaration order. The view
     *         is consistent at the moment of each element production but not transactionally
     *         consistent across components.
     */
    Stream<ComponentSnapshot> allSnapshots() {
        return containers.values().stream()
                .flatMap(ParadigmContainer::snapshots)
                .map(s -> s);
    }

    /**
     * @return a stream over every bulkhead snapshot across every paradigm. Built on top of
     *         {@link #allSnapshots()} and filtered down to {@link BulkheadSnapshot}.
     */
    Stream<BulkheadSnapshot> bulkheadSnapshots() {
        return allSnapshots()
                .filter(BulkheadSnapshot.class::isInstance)
                .map(BulkheadSnapshot.class::cast);
    }

    /**
     * @return the bulkhead snapshot for the given {@code (name, paradigm)} tuple, if configured.
     *         Looks up the paradigm's container, then filters its snapshot stream by name and
     *         {@link BulkheadSnapshot} type — no {@code instanceof} on the container itself.
     */
    Optional<BulkheadSnapshot> findBulkhead(String name, ParadigmTag paradigm) {
        ParadigmContainer<?> container = containers.get(paradigm);
        if (container == null) {
            return Optional.empty();
        }
        return container.snapshots()
                .filter(s -> s.name().equals(name))
                .filter(BulkheadSnapshot.class::isInstance)
                .map(BulkheadSnapshot.class::cast)
                .findFirst();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
    }

    /**
     * Test-friendly accessor. Package-private so tests in the same package may inspect closure
     * state without exposing it to user code.
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Aggregating cross-paradigm view backed by the runtime's container map.
     */
    private static final class DefaultInqConfigView implements InqConfigView {

        private final DefaultInqRuntime runtime;

        DefaultInqConfigView(DefaultInqRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public GeneralSnapshot general() {
            return runtime.general();
        }

        @Override
        public Stream<ComponentSnapshot> all() {
            return runtime.allSnapshots();
        }

        @Override
        public Stream<BulkheadSnapshot> bulkheads() {
            return runtime.bulkheadSnapshots();
        }

        @Override
        public Optional<BulkheadSnapshot> findBulkhead(String name, ParadigmTag paradigm) {
            return runtime.findBulkhead(name, paradigm);
        }
    }
}
