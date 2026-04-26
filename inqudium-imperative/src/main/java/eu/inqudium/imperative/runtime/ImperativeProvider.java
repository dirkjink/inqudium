package eu.inqudium.imperative.runtime;

import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.bulkhead.dsl.DefaultImperativeBulkheadBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Imperative paradigm provider. Registered via
 * {@code META-INF/services/eu.inqudium.config.spi.ParadigmProvider} so the runtime builder
 * discovers it through {@link java.util.ServiceLoader}.
 *
 * <p>Provides:
 *
 * <ul>
 *   <li>The paradigm tag, {@link ImperativeTag#INSTANCE},</li>
 *   <li>The {@link DefaultImperativeBulkheadBuilder} factory used by the DSL section, and</li>
 *   <li>The {@link DefaultImperative} container assembly that takes a paradigm-section's worth of
 *       {@link BulkheadPatch} instances plus the {@link GeneralSnapshot} and produces the live
 *       {@code InqBulkhead} components.</li>
 * </ul>
 */
public final class ImperativeProvider implements ParadigmProvider {

    /**
     * System-default snapshot used as the apply-base for incoming bulkhead patches. Touched
     * fields take the patch's value; untouched fields fall back to these defaults — currently
     * the same baseline as the {@code balanced} preset to keep "user wrote nothing" behaviour
     * predictable.
     *
     * @param name the bulkhead's name; the patch's {@code NAME} field touch overrides this on
     *             apply, so the placeholder here is never observable.
     */
    private static BulkheadSnapshot defaultSnapshot(String name) {
        return new BulkheadSnapshot(name, 50, Duration.ofMillis(500), Set.of(), null);
    }

    @Override
    public ParadigmTag paradigm() {
        return ImperativeTag.INSTANCE;
    }

    @Override
    public BulkheadBuilderBase<?> createBulkheadBuilder(String name) {
        return new DefaultImperativeBulkheadBuilder(name);
    }

    @Override
    public ParadigmContainer<?> createContainer(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        Map<String, InqBulkhead> bulkheads = new LinkedHashMap<>();
        for (Map.Entry<String, BulkheadPatch> entry : patches.bulkheadPatches().entrySet()) {
            String name = entry.getKey();
            BulkheadPatch patch = entry.getValue();
            BulkheadSnapshot initial = patch.applyTo(defaultSnapshot(name));
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(initial);
            bulkheads.put(name, new InqBulkhead(live, general));
        }
        return new DefaultImperative(bulkheads);
    }
}
