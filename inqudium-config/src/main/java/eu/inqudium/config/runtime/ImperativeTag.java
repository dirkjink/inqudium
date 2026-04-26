package eu.inqudium.config.runtime;

/**
 * Type tag identifying the imperative paradigm.
 *
 * <p>The tag is a singleton enum: it carries no runtime data, has free {@code equals}/
 * {@code hashCode}, and produces a stable identity for use in maps and switch statements. It is
 * the type argument of {@link eu.inqudium.config.dsl.ImperativeBulkheadBuilder
 * ImperativeBulkheadBuilder} and — in step&nbsp;1.7 — of the runtime's {@code Imperative}
 * paradigm container.
 */
public enum ImperativeTag implements ParadigmTag {

    /** The single imperative tag instance. */
    INSTANCE
}
