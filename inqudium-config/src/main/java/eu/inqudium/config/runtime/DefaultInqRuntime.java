package eu.inqudium.config.runtime;

import eu.inqudium.config.dsl.InqudiumUpdateBuilder;
import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
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
 * <p>Phase&nbsp;1 stubs {@link #update}, {@link #apply}, {@link #dryRun}, and {@link #diagnose}
 * — the runtime is queryable but not mutable through these entry points. Step&nbsp;1.7-D wires
 * the update path; phases&nbsp;2 and beyond complete the rest.
 */
public final class DefaultInqRuntime implements InqRuntime {

    private final GeneralSnapshot general;
    private final Map<ParadigmTag, ParadigmContainer<?>> containers;
    private final InqConfigView configView;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DefaultInqRuntime(
            GeneralSnapshot general,
            Map<ParadigmTag, ParadigmContainer<?>> containers) {
        this.general = Objects.requireNonNull(general, "general");
        this.containers = Map.copyOf(Objects.requireNonNull(containers, "containers"));
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
        // Phase 1.7-D wires this up via the update DSL pipeline.
        throw new UnsupportedOperationException(
                "runtime.update is wired in step 1.7-D; not yet available in this build.");
    }

    @Override
    public BuildReport apply(List<? extends ComponentPatch<?>> patches) {
        ensureOpen();
        // Phase 1.7-D wires this up alongside update.
        throw new UnsupportedOperationException(
                "runtime.apply is wired in step 1.7-D; not yet available in this build.");
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
     * @return the per-paradigm container map, for the config view.
     */
    Map<ParadigmTag, ParadigmContainer<?>> containers() {
        return containers;
    }

    /**
     * @return a stream over every bulkhead snapshot across every paradigm.
     */
    Stream<BulkheadSnapshot> bulkheadSnapshots() {
        Stream<BulkheadSnapshot> result = Stream.empty();
        ParadigmContainer<?> imperative = containers.get(ImperativeTag.INSTANCE);
        if (imperative instanceof Imperative imp) {
            result = Stream.concat(result,
                    imp.bulkheadNames().stream()
                            .map(imp::bulkhead)
                            .map(BulkheadHandle::snapshot));
        }
        return result;
    }

    /**
     * @return the bulkhead snapshot for the given (name, paradigm) tuple, if configured.
     */
    Optional<BulkheadSnapshot> findBulkhead(String name, ParadigmTag paradigm) {
        ParadigmContainer<?> container = containers.get(paradigm);
        if (container instanceof Imperative imp) {
            return imp.findBulkhead(name).map(BulkheadHandle::snapshot);
        }
        return Optional.empty();
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
        public Stream<eu.inqudium.config.snapshot.ComponentSnapshot> all() {
            return runtime.bulkheadSnapshots().map(s -> s);
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

    // Suppress IDE warnings on unused import; ApplyOutcome is referenced by the BuildReport
    // shape in update/apply once 1.7-D wires them up.
    @SuppressWarnings("unused")
    private static final ApplyOutcome OUTCOME_PLACEHOLDER = ApplyOutcome.UNCHANGED;

    /**
     * Build a placeholder LinkedHashMap-backed component-outcomes container. Used by 1.7-D to
     * collect per-component outcomes during update.
     */
    static Map<String, ApplyOutcome> emptyOutcomes() {
        return new LinkedHashMap<>();
    }
}
