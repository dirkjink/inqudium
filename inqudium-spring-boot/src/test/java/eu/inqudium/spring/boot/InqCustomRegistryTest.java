package eu.inqudium.spring.boot;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.spring.InqShieldAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a user-defined {@link InqElementRegistry} bean takes
 * precedence over the auto-configured one via {@code @ConditionalOnMissingBean},
 * even when other {@link InqElement} beans are present in the context that
 * <em>would otherwise</em> be auto-discovered.
 *
 * <p>The configuration intentionally provides:</p>
 * <ul>
 *   <li>A user-defined {@link InqElementRegistry} containing only {@code customCb}.</li>
 *   <li>A separate {@link InqElement} bean named {@code autoDiscovered} that
 *       would land in the auto-configured registry if the override gate failed.</li>
 * </ul>
 *
 * <p>If {@code @ConditionalOnMissingBean} works correctly, the user registry
 * is used as-is and {@code autoDiscovered} does <em>not</em> appear in it
 * (the auto-discovery {@code @Bean} method is skipped entirely). The
 * {@code autoDiscovered} bean is still managed by Spring — it is just not
 * in the registry. This is what proves the override path is deterministic
 * and not a side-effect of an empty context.</p>
 *
 * <p>This test deliberately stays flat (no {@code @Nested} grouping). When
 * non-static {@code @Nested} test classes coexist with the static
 * {@code @Configuration} inner class in the same outer test, Spring Boot's
 * {@code TestTypeExcludeFilter} stops excluding the configuration from
 * other tests' default component scans. The flat shape keeps
 * {@link InqSpringBootIntegrationTest}'s context isolated.</p>
 */
@SpringBootTest(classes = InqCustomRegistryTest.CustomRegistryConfig.class)
@DisplayName("Custom registry overrides auto-configuration")
class InqCustomRegistryTest {

    @Autowired
    InqElementRegistry registry;

    @Autowired
    ApplicationContext context;

    @Autowired
    InqShieldAspect aspect;

    @Test
    void user_registry_contains_only_the_manually_registered_element() {
        // Given — the user provided a registry pre-populated with "customCb"
        //         and a separate autoDiscovered InqElement bean exists in the context.

        // Then — only the manually registered element is in the registry.
        //        The autoDiscovered bean is NOT injected into the user registry,
        //        because @ConditionalOnMissingBean skips the auto-discovery method
        //        once the user provides their own InqElementRegistry bean.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.contains("customCb")).isTrue();
        assertThat(registry.contains("autoDiscovered")).isFalse();
    }

    @Test
    void auto_discovered_element_bean_still_exists_as_a_spring_bean() {
        // What is being tested: that the autoDiscovered InqElement bean is
        //                       reachable directly as a Spring bean even though
        //                       it never made it into the user registry.
        // Why it counts as success: the bean is fetchable by name and reports
        //                           the expected element name, proving Spring
        //                           still managed it — only the auto-discovery
        //                           wiring into the registry was skipped.
        // Why this matters: it pins the override path to "user registry replaces
        //                   the auto-configured one", not "user registry suppresses
        //                   all InqElement beans". A future regression in
        //                   @ConditionalOnMissingBean wiring would otherwise be
        //                   indistinguishable from a context with no elements.

        // When
        InqElement bean = context.getBean("autoDiscovered", InqElement.class);

        // Then
        assertThat(bean).isNotNull();
        assertThat(bean.name()).isEqualTo("autoDiscovered");
    }

    @Test
    void inq_shield_aspect_bean_exists_when_only_the_registry_is_overridden() {
        // What is being tested: when the user replaces only the registry, the
        //                       second @Bean method in InqAutoConfiguration
        //                       (inqShieldAspect) must still fire and produce a
        //                       working aspect wired against the user registry.
        // Why it counts as success: the InqShieldAspect bean is autowired and
        //                           non-null in the test instance.
        // Why this matters: a naive @ConditionalOnMissingBean on the configuration
        //                   class itself (rather than per-bean) would suppress the
        //                   aspect too. We need both bean methods to gate
        //                   independently — only the registry method is skipped,
        //                   not the aspect method.

        // Then
        assertThat(aspect).isNotNull();
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    /**
     * Configuration that provides a custom {@link InqElementRegistry} and an
     * additional standalone {@link InqElement} bean. The latter would be picked
     * up by auto-discovery if the {@code @ConditionalOnMissingBean} guard on
     * the auto-configured registry failed.
     */
    @Configuration
    @EnableAutoConfiguration
    static class CustomRegistryConfig {

        @Bean
        public InqElementRegistry inqElementRegistry() {
            return InqElementRegistry.builder()
                    .register("customCb", new StubElement("customCb"))
                    .build();
        }

        @Bean
        public InqElement autoDiscovered() {
            return new StubElement("autoDiscovered");
        }

        static class StubElement implements InqDecorator<Void, Object> {
            private final String name;

            StubElement(String name) {
                this.name = name;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public InqElementType elementType() {
                return InqElementType.CIRCUIT_BREAKER;
            }

            @Override
            public InqEventPublisher eventPublisher() {
                return null;
            }

            @Override
            public Object execute(long chainId, long callId, Void arg,
                                  InternalExecutor<Void, Object> next) {
                return next.execute(chainId, callId, arg);
            }
        }
    }
}
