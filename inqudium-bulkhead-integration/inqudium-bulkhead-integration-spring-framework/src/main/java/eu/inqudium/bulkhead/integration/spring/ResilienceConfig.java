package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.spring.InqShieldAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Plain-Spring (no Boot) configuration that wires Inqudium into the application context.
 *
 * <p>The class makes the four moving parts of an Inqudium-on-Spring setup explicit:
 * <ol>
 *   <li>the {@link InqRuntime} bean — the owner of every Inqudium component, with
 *       {@code destroyMethod = "close"} so the runtime closes when the application context
 *       shuts down,</li>
 *   <li>the {@link InqElementRegistry} bean — populated by hand from the runtime's bulkhead
 *       handle. In Spring Boot this happens automatically via {@code InqAutoConfiguration}
 *       discovering all {@link InqElement} beans; here it is the application's
 *       responsibility to register each element by name, which is the deliberate point of
 *       the example,</li>
 *   <li>the {@link InqShieldAspect} bean — the resilience aspect from
 *       {@code inqudium-spring}, fed the registry so it can resolve names at advice time,</li>
 *   <li>the {@link OrderService} bean — declared explicitly to keep the configuration
 *       "everything visible" rather than relying on {@code @ComponentScan}.</li>
 * </ol>
 *
 * <p>The single most important line is {@link EnableAspectJAutoProxy @EnableAspectJAutoProxy}:
 * without it Spring would not generate the AOP proxy that routes calls through {@link
 * InqShieldAspect}, and every {@code @InqBulkhead}-annotated call on {@link OrderService}
 * would go straight to the method body — the bulkhead would never be invoked. Spring Boot
 * adds this annotation implicitly when {@code spring-boot-starter-aop} is on the classpath
 * and {@code spring.aop.auto} is left at its default; in plain Spring it is the application's
 * responsibility to opt in.
 */
@Configuration
@EnableAspectJAutoProxy
public class ResilienceConfig {

    /**
     * The Inqudium runtime, owned by Spring. The {@code destroyMethod = "close"} attribute
     * ensures the runtime is closed when the application context shuts down, so paradigm
     * containers tear down, strategies release their locks, and the runtime-scoped event
     * publisher releases.
     */
    @Bean(destroyMethod = "close")
    public InqRuntime inqRuntime() {
        return BulkheadConfig.newRuntime();
    }

    /**
     * The element registry, populated explicitly from the runtime's bulkhead handle.
     *
     * <p>In Spring Boot this happens automatically: {@code InqAutoConfiguration} discovers
     * every {@link InqElement} bean in the application context and indexes it by
     * {@link InqElement#name()}. Plain Spring
     * has no equivalent discovery mechanism, so this method is what makes the bulkhead
     * resolvable by the name {@link BulkheadConfig#BULKHEAD_NAME} at advice time. Each
     * additional bulkhead (or other element) the application configures must be added here
     * by hand — pedagogically that is the point: the example shows what the auto-config
     * would otherwise do for the user.
     */
    @Bean
    public InqElementRegistry inqElementRegistry(InqRuntime runtime) {
        InqElementRegistry registry = InqElementRegistry.create();
        registry.register(
                BulkheadConfig.BULKHEAD_NAME,
                (InqElement) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME));
        return registry;
    }

    /**
     * The Inqudium aspect that intercepts {@code @InqBulkhead}-annotated methods (and every
     * other Inqudium element annotation) and routes them through the registered elements.
     * Required by {@link EnableAspectJAutoProxy} on the configuration class — without that
     * annotation the aspect would be a regular bean and Spring would never apply its
     * {@code @Around} advice.
     */
    @Bean
    public InqShieldAspect inqShieldAspect(InqElementRegistry registry) {
        return new InqShieldAspect(registry);
    }

    /**
     * The {@link OrderService} bean. Could equivalently be discovered via
     * {@code @ComponentScan} since the class carries {@link
     * org.springframework.stereotype.Service @Service}; declared explicitly here to keep the
     * configuration "everything visible" — a reader scanning {@code ResilienceConfig} sees
     * every bean the example contributes without having to consult a scan path.
     */
    @Bean
    public OrderService orderService() {
        return new OrderService();
    }
}
