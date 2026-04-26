package eu.inqudium.config.runtime;

/**
 * Sealed marker for the per-paradigm type tags used as type parameters on paradigm-typed builders
 * and runtime containers.
 *
 * <p>Each paradigm contributes one concrete tag — currently only {@link ImperativeTag}; future
 * phases add {@code ReactiveTag}, {@code RxJava3Tag}, and {@code CoroutinesTag}. Tags carry no
 * runtime data; they exist to make the paradigm a compile-time fact so that
 * {@code BulkheadBuilder&lt;ImperativeTag&gt;} cannot accidentally be used where a reactive
 * builder is expected, and vice versa.
 *
 * <p>The {@code permits} clause is updated as new paradigm modules join the build.
 */
public sealed interface ParadigmTag permits ImperativeTag {
}
