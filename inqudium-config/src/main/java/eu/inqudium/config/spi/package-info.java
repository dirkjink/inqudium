/**
 * Service-provider interfaces for paradigm modules.
 *
 * <p>{@link eu.inqudium.config.spi.ParadigmProvider ParadigmProvider} is the single SPI that links
 * a paradigm module ({@code inqudium-imperative}, {@code inqudium-reactive},
 * {@code inqudium-rxjava3}, {@code inqudium-kotlin}) into the runtime. Each provider declares its
 * {@code ParadigmTag} and supplies the factories needed to materialize the paradigm-specific
 * builders and containers. Providers are discovered via {@link java.util.ServiceLoader}; no
 * paradigm module is referenced statically from {@code inqudium-config}.
 *
 * <p>If a paradigm is referenced in the DSL but no provider for it is on the classpath, the
 * runtime raises {@link eu.inqudium.config.runtime.ParadigmUnavailableException
 * ParadigmUnavailableException} with a message naming the missing module.
 */
package eu.inqudium.config.spi;
