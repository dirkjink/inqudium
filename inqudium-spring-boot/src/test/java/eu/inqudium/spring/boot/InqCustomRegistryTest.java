package eu.inqudium.spring.boot;

import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a user-defined {@link InqElementRegistry} bean takes
 * precedence over the auto-configured one via {@code @ConditionalOnMissingBean}.
 *
 * <p>This test lives in a separate class (not as a {@code @Nested} inner class
 * of {@link InqSpringBootIntegrationTest}) to avoid Spring Boot test context
 * interference: a {@code @SpringBootTest} on a nested class can pollute
 * the enclosing class's {@code @Autowired} fields via context caching.</p>
 */
@SpringBootTest(classes = InqCustomRegistryTest.CustomRegistryConfig.class)
@DisplayName("Custom registry overrides auto-configuration")
class InqCustomRegistryTest {

    @Autowired
    InqElementRegistry registry;

    @Test
    void custom_registry_bean_replaces_auto_configured_one() {
        // Then — only the manually registered element, not auto-discovered
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.contains("customCb")).isTrue();
    }

    @Test
    void auto_discovered_elements_are_not_in_custom_registry() {
        // Then — "orderCb" etc. are NOT registered
        assertThat(registry.contains("orderCb")).isFalse();
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    /**
     * Configuration that provides a custom {@link InqElementRegistry},
     * causing {@code @ConditionalOnMissingBean} in {@link InqAutoConfiguration}
     * to skip auto-discovery.
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
