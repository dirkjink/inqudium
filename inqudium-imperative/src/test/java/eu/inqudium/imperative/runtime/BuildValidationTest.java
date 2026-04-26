package eu.inqudium.imperative.runtime;

import eu.inqudium.config.ConfigurationException;
import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.Severity;
import eu.inqudium.config.validation.ValidationFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Build-time validation pipeline")
class BuildValidationTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void should_produce_a_successful_BuildReport_for_a_valid_configuration() {
            // What is to be tested: that a configuration that triggers no class-3 rules
            // produces a successful BuildReport reachable via runtime.lastBuildReport().
            // Why successful: isSuccess() == true and findings is empty.
            // Why important: the happy path must not generate spurious noise; users without any
            // class-3 issues should see a clean report.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.lastBuildReport();
                assertThat(report.isSuccess()).isTrue();
                assertThat(report.findings()).isEmpty();
                assertThat(report.vetoFindings()).isEmpty();
            }
        }

        @Test
        void should_succeed_for_an_empty_runtime() {
            try (InqRuntime runtime = Inqudium.configure().build()) {
                assertThat(runtime.lastBuildReport().isSuccess()).isTrue();
                assertThat(runtime.lastBuildReport().findings()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("class-3 rule warnings")
    class ClassThreeWarnings {

        @Test
        void should_report_BULKHEAD_PROTECTIVE_WITH_LONG_WAIT_warning_in_lenient_mode() {
            // What is to be tested: that the protective+long-wait combination produces a warning
            // in the BuildReport but does not abort the build.
            // Why successful: runtime is constructed, lastBuildReport().findings() contains the
            // expected rule, severity is WARNING, isSuccess() remains true.
            // Why important: this is the central acceptance criterion of phase 1.8 — class-3
            // rules surface in the report without breaking lenient builds.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.protective().maxWaitDuration(Duration.ofSeconds(5))))
                    .build()) {

                BuildReport report = runtime.lastBuildReport();
                assertThat(report.isSuccess()).isTrue();
                assertThat(report.findings()).hasSize(1);
                ValidationFinding f = report.findings().get(0);
                assertThat(f.ruleId()).isEqualTo("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
                assertThat(f.componentName()).isEqualTo("inventory");
            }
        }

        @Test
        void warnings_should_be_visible_via_BuildReport_warnings_stream() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.protective().maxWaitDuration(Duration.ofSeconds(5))))
                    .build()) {

                assertThat(runtime.lastBuildReport().warnings()
                        .map(ValidationFinding::ruleId)
                        .toList())
                        .containsExactly("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
            }
        }
    }

    @Nested
    @DisplayName("strict mode")
    class StrictMode {

        @Test
        void should_throw_ConfigurationException_when_strict_and_warnings_present() {
            // What is to be tested: the strict-mode contract — warnings elevated to errors
            // abort the build with a ConfigurationException carrying the report.
            // Why successful: ConfigurationException is thrown; its report() carries one
            // ERROR-level finding for the elevated warning rule.
            // Why important: strict mode is the CI/test-friendly policy; a strict build that
            // silently passes despite class-3 warnings would be a regression.

            // Given / When / Then
            assertThatThrownBy(() -> Inqudium.configure()
                    .strict()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.protective().maxWaitDuration(Duration.ofSeconds(5))))
                    .build())
                    .isInstanceOfSatisfying(ConfigurationException.class, ex -> {
                        BuildReport report = ex.report();
                        assertThat(report.isSuccess()).isFalse();
                        assertThat(report.findings()).hasSize(1);
                        ValidationFinding f = report.findings().get(0);
                        assertThat(f.ruleId()).isEqualTo("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
                        assertThat(f.severity())
                                .as("warning elevated to error in strict mode")
                                .isEqualTo(Severity.ERROR);
                    });
        }

        @Test
        void should_not_throw_when_strict_and_no_warnings_present() {
            // Strict mode is only relevant when there are warnings to elevate. Without warnings,
            // strict and lenient produce the same successful report.

            try (InqRuntime runtime = Inqudium.configure()
                    .strict()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                assertThat(runtime.lastBuildReport().isSuccess()).isTrue();
                assertThat(runtime.lastBuildReport().findings()).isEmpty();
            }
        }

        @Test
        void exception_message_should_name_the_failing_rule() {
            // Given / When / Then
            assertThatThrownBy(() -> Inqudium.configure()
                    .strict()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.protective().maxWaitDuration(Duration.ofSeconds(5))))
                    .build())
                    .hasMessageContaining("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
        }
    }
}
