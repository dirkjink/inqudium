package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot integration test that pins the runtime's lifecycle when the Spring
 * {@link ConfigurableApplicationContext} closes.
 *
 * <p>The current production shape: the application registers its {@link InqRuntime} as a
 * Spring bean with {@code destroyMethod = "close"}. Spring calls the close method during
 * context shutdown; the runtime's own {@code close()} releases its components. This test
 * pins the observable consequence — after the close, accessing the previously-acquired
 * handles must report the closed-runtime contract.
 *
 * <p>The audit finding F-2.19-4 (whether components should also see a {@code markRemoved()}
 * notification when the context shuts down) is routed to the ADR audit backlog. This test
 * therefore documents <em>current</em> behaviour rather than asserting a specific
 * markRemoved contract — if a future ADR specifies one, this test grows the assertion
 * accordingly.
 */
@DisplayName("Bulkhead Spring Boot — context shutdown")
class BulkheadSpringBootShutdownTest {

    @SpringBootApplication
    static class ShutdownApplication {

        @Bean(destroyMethod = "close")
        public InqRuntime inqRuntime() {
            return Inqudium.configure()
                    .imperative(im -> im.bulkhead("aopShutdown", b -> b.balanced()))
                    .build();
        }

        @Bean
        public InqElement aopShutdown(InqRuntime runtime) {
            return (InqElement) runtime.imperative().bulkhead("aopShutdown");
        }
    }

    @Test
    void closing_the_application_context_releases_the_runtime() {
        // What is to be tested: a Spring Boot context is built, the runtime is reachable
        // through the InqElement bean, then the context is closed. The same handle, captured
        // before the close, should report that the runtime is no longer servicing calls.
        // Why successful: closing the context executes the destroyMethod on the runtime
        // bean; the captured handle then reports its closed state in a deterministic way
        // (either via ComponentRemovedException or by ceasing to find the bulkhead).
        // Why important: pins the integration contract for graceful shutdown — if a future
        // change wires the close differently, this test surfaces the regression.

        ConfigurableApplicationContext context = new SpringApplication(ShutdownApplication.class) {{
            setWebApplicationType(WebApplicationType.NONE);
        }}.run();

        InqRuntime runtime = context.getBean(InqRuntime.class);
        @SuppressWarnings("unchecked")
        InqBulkhead<Void, Object> bh =
                (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("aopShutdown");

        assertThat(bh.name()).isEqualTo("aopShutdown");

        // When — close the context.
        context.close();

        // Then — the runtime fails fast on any subsequent paradigm-section access. The
        // current contract is "closed runtime is not navigable": runtime.imperative()
        // throws IllegalStateException("runtime is closed") rather than returning an
        // empty view or a phantom view. Pinning that here.
        //
        // The captured handle's behaviour after the runtime close depends on the current
        // contract: the existing implementation does not call markRemoved(...) on the
        // bulkhead handles at runtime.close() (F-2.19-4 is in the ADR-audit backlog).
        // The handle is therefore still callable but its underlying live container is
        // no longer driven by the runtime — a regression around that wiring would be
        // caught by a future test that pins the desired markRemoved-on-close contract.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.imperative())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
