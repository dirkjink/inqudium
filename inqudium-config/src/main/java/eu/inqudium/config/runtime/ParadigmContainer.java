package eu.inqudium.config.runtime;

/**
 * Per-paradigm container surface — the registry-equivalent for one paradigm.
 *
 * <p>Each paradigm extends this interface with its own typed accessors ({@link Imperative} adds
 * {@code bulkhead(name)}, {@code findBulkhead(name)}, ...). The base interface carries only the
 * paradigm tag so generic code can introspect a {@code ParadigmContainer<?>} without knowing the
 * specific paradigm at compile time.
 *
 * @param <P> the paradigm tag.
 */
public interface ParadigmContainer<P extends ParadigmTag> {

    /**
     * @return the tag identifying this container's paradigm.
     */
    ParadigmTag paradigm();
}
