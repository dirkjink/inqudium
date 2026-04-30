package eu.inqudium.spring.boot;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.spring.InqShieldAspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for the Inqudium resilience pipeline.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li><strong>Discovers</strong> all {@link InqElement} beans in the
 *       application context (CircuitBreaker, Retry, Bulkhead, etc.)</li>
 *   <li><strong>Registers</strong> them in an {@link InqElementRegistry}
 *       using {@link InqElement#name()} as the lookup key</li>
 *   <li><strong>Creates</strong> an {@link InqShieldAspect} that intercepts
 *       methods annotated with {@code @InqCircuitBreaker}, {@code @InqRetry},
 *       etc. and routes them through the pipeline</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <p>Add {@code inqudium-spring-boot} to your classpath — auto-configuration activates
 * automatically. Components live on an {@link eu.inqudium.config.runtime.InqRuntime} which
 * you declare as a Spring bean; expose each component handle as an {@link InqElement} bean
 * so auto-configuration discovers it:</p>
 * <pre>{@code
 * @Configuration
 * public class ResilienceConfig {
 *
 *     @Bean(destroyMethod = "close")
 *     public InqRuntime inqRuntime() {
 *         return Inqudium.configure()
 *                 .imperative(im -> im
 *                         .bulkhead("paymentBh", b -> b.balanced())
 *                         .retry("paymentRetry", r -> r.attempts(3)))
 *                 .build();
 *     }
 *
 *     @Bean
 *     public InqElement paymentBh(InqRuntime runtime) {
 *         return (InqElement) runtime.imperative().bulkhead("paymentBh");
 *     }
 *
 *     @Bean
 *     public InqElement paymentRetry(InqRuntime runtime) {
 *         return (InqElement) runtime.imperative().retry("paymentRetry");
 *     }
 * }
 * }</pre>
 *
 * <p>Then annotate your service methods:</p>
 * <pre>{@code
 * @Service
 * public class PaymentService {
 *
 *     @InqBulkhead("paymentBh")
 *     @InqRetry("paymentRetry")
 *     public PaymentResult processPayment(PaymentRequest request) {
 *         return remoteService.call(request);
 *     }
 * }
 * }</pre>
 *
 * <p>The legacy {@code CircuitBreaker.of(config)} / {@code Retry.of(config)} static
 * factories no longer exist: every component is materialized through the runtime
 * builder, and the corresponding {@code @Bean InqElement} method is what makes the
 * handle discoverable to auto-configuration.</p>
 *
 * <h3>Customization</h3>
 * <p>Define your own {@link InqElementRegistry} bean to override the
 * auto-discovered one:</p>
 * <pre>{@code
 * @Bean
 * public InqElementRegistry customRegistry() {
 *     return InqElementRegistry.builder()
 *             .register("paymentCb", myCustomCb)
 *             .build();
 * }
 * }</pre>
 *
 * @since 0.8.0
 */
@AutoConfiguration
@ConditionalOnClass(InqElementRegistry.class)
public class InqAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InqAutoConfiguration.class);

    /**
     * Discovers all {@link InqElement} beans and registers them by name.
     *
     * <p>Each element's {@link InqElement#name()} is used as the
     * registry key. If two elements share the same name, an
     * {@link IllegalStateException} is thrown at startup to prevent
     * ambiguous configurations from reaching production.</p>
     *
     * <p>If a custom {@link InqElementRegistry} bean is already defined,
     * this auto-configured one is skipped.</p>
     *
     * @param elements all InqElement beans discovered by Spring
     * @return the populated registry
     * @throws IllegalStateException if two elements have the same name
     */
    @Bean
    @ConditionalOnMissingBean
    public InqElementRegistry inqElementRegistry(List<InqElement> elements) {
        InqElementRegistry registry = InqElementRegistry.create();

        for (InqElement element : elements) {
            InqElement previous = registry.register(element.name(), element);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate InqElement name '" + element.name()
                                + "': bean of type " + previous.getClass().getName()
                                + " is already registered, but a second bean of type "
                                + element.getClass().getName()
                                + " has the same name. Each InqElement bean must have a "
                                + "unique name. Rename one of the beans or provide a "
                                + "custom InqElementRegistry to resolve the conflict.");
            }
        }

        log.info("InqElementRegistry initialized with {} element(s): {}",
                registry.size(), registry.names());

        return registry;
    }

    /**
     * Creates the Spring AOP aspect that intercepts annotated methods
     * and routes them through the Inqudium pipeline.
     *
     * @param registry the element registry for name → instance lookup
     * @return the shield aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public InqShieldAspect inqShieldAspect(InqElementRegistry registry) {
        return new InqShieldAspect(registry);
    }
}
