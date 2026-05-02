package eu.inqudium.bulkhead.integration.springboot;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;

/**
 * Single point where Inqudium is configured for this example.
 *
 * <p>The configuration is identical to the function-based, proxy-based, AspectJ-native, and
 * plain-Spring examples: one bulkhead named {@code orderBh}, the {@code balanced} preset
 * overridden to two concurrent permits. The configuration code does not change between
 * integration styles — the runtime is the same; only the wiring on top differs. This module
 * exercises the runtime through Spring AOP applied by Spring Boot's auto-configured
 * {@link org.springframework.context.ApplicationContext ApplicationContext}; Inqudium's
 * {@link eu.inqudium.spring.boot.InqAutoConfiguration} discovers the {@link
 * eu.inqudium.core.element.InqElement InqElement} bean exposed by {@link
 * OrderServiceApplication} and registers it without any manual registry plumbing.
 */
public final class BulkheadConfig {

    /** The name under which the example's bulkhead is registered. */
    public static final String BULKHEAD_NAME = "orderBh";

    private BulkheadConfig() {
        // utility class
    }

    /**
     * @return a freshly built runtime with a single bulkhead named {@link #BULKHEAD_NAME}.
     *         The runtime is independent of any other call site and must be closed by its
     *         caller — typically via Spring's {@code @Bean(destroyMethod = "close")} so the
     *         application context owns the close call.
     */
    public static InqRuntime newRuntime() {
        return Inqudium.configure()
                .imperative(im -> im.bulkhead(BULKHEAD_NAME,
                        b -> b.balanced().maxConcurrentCalls(2)))
                .build();
    }
}
