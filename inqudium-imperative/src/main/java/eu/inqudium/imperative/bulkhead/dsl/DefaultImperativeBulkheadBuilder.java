package eu.inqudium.imperative.bulkhead.dsl;

import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.config.dsl.ImperativeBulkheadBuilder;
import eu.inqudium.config.runtime.ImperativeTag;

/**
 * Concrete imperative bulkhead builder.
 *
 * <p>In phase&nbsp;1.5 this class is a thin shell over
 * {@link BulkheadBuilderBase BulkheadBuilderBase&lt;ImperativeTag&gt;} — every setter is inherited
 * unchanged. Imperative-specific extensions (strategy injection, adaptive-limit sub-builders) are
 * planned for later phases and will be added here.
 */
public final class DefaultImperativeBulkheadBuilder
        extends BulkheadBuilderBase<ImperativeTag>
        implements ImperativeBulkheadBuilder {

    /**
     * @param name the bulkhead's name; non-null and non-blank. Validated by the base class.
     */
    public DefaultImperativeBulkheadBuilder(String name) {
        super(name);
    }
}
