package eu.inqudium.core.element.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extensive tests for FailurePredicateConfigBuilder.
 * Following the requirements: Given/When/Then, AssertJ, Snake Case names, and Nested categories.
 */
class FailurePredicateConfigBuilderTest {

  @Nested
  @DisplayName("Tests for default behavior and base inclusion")
  class BaseInclusionTests {

    @Test
    @DisplayName("Should record everything when the builder is empty and not configured")
    void should_record_everything_when_the_builder_is_empty_and_not_configured() {
      // Given
      // A fresh builder without any calls to record, ignore or override methods
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder();

      // When
      FailurePredicateConfig config = builder.build();
      Predicate<Throwable> predicate = config.finalPredicate();

      // Then
      // According to the logic: hasBaseRecordRules is false, so isBaseIncluded returns true.
      assertThat(predicate.test(new RuntimeException()))
          .as("Should record RuntimeException by default")
          .isTrue();

      assertThat(predicate.test(new Exception()))
          .as("Should record checked Exception by default")
          .isTrue();

      assertThat(predicate.test(new Error()))
          .as("Should record Errors by default")
          .isTrue();
    }

    @Test
    @DisplayName("Should only record specified exception classes when record rules exist")
    void should_only_record_specified_exception_classes_when_record_rules_exist() {
      // Given
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .recordExceptions(IOException.class);
      FailurePredicateConfig config = builder.build();

      Throwable recorded = new IOException();
      Throwable ignored = new RuntimeException();

      // When
      boolean isRecorded = config.finalPredicate().test(recorded);
      boolean isIgnored = config.finalPredicate().test(ignored);

      // Then
      assertThat(isRecorded).as("IOException should be recorded").isTrue();
      assertThat(isIgnored).as("RuntimeException should be ignored as it's not in the record list").isFalse();
    }
  }

  @Nested
  @DisplayName("Tests for base exclusion (ignore rules)")
  class BaseExclusionTests {

    @Test
    @DisplayName("Should ignore specific exception classes even if they would normally be included")
    void should_ignore_specific_exception_classes_even_if_they_would_normally_be_included() {
      // Given
      // Default is "record all", but we explicitly ignore IllegalArgumentException
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .ignoreExceptions(IllegalArgumentException.class);
      FailurePredicateConfig config = builder.build();

      Throwable exception = new IllegalArgumentException();

      // When
      boolean result = config.finalPredicate().test(exception);

      // Then
      assertThat(result).as("Exception should be ignored via base exclusion").isFalse();
    }

    @Test
    @DisplayName("Should ignore exceptions based on custom predicates")
    void should_ignore_exceptions_based_on_custom_predicates() {
      // Given
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .ignoreWhen(t -> t.getMessage().contains("ignore-me"));
      FailurePredicateConfig config = builder.build();

      Throwable ignored = new RuntimeException("Please ignore-me");
      Throwable recorded = new RuntimeException("Keep me");

      // When / Then
      assertThat(config.finalPredicate().test(ignored)).isFalse();
      assertThat(config.finalPredicate().test(recorded)).isTrue();
    }
  }

  @Nested
  @DisplayName("Tests for override logic (Super Rules)")
  class OverrideTests {

    @Test
    @DisplayName("Should force record an exception even if it is on the ignore list")
    void should_force_record_an_exception_even_if_it_is_on_the_ignore_list() {
      // Given
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .ignoreExceptions(RuntimeException.class)
          .alwaysRecordWhen(t -> t instanceof RuntimeException);
      FailurePredicateConfig config = builder.build();

      Throwable exception = new RuntimeException();

      // When
      boolean result = config.finalPredicate().test(exception);

      // Then
      assertThat(result).as("Force record should override base ignore rules").isTrue();
    }

    @Test
    @DisplayName("Should force ignore an exception regardless of any other record rules")
    void should_force_ignore_an_exception_regardless_of_any_other_record_rules() {
      // Given
      // Priority: Force Ignore (Veto) > Force Record > Standard Evaluation
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .recordExceptions(IOException.class)
          .alwaysRecordWhen(t -> t instanceof IOException)
          .alwaysIgnoreWhen(t -> t.getMessage().equals("Veto"));

      FailurePredicateConfig config = builder.build();
      Throwable exception = new IOException("Veto");

      // When
      boolean result = config.finalPredicate().test(exception);

      // Then
      assertThat(result).as("Force ignore (Veto) should have the highest priority").isFalse();
    }
  }

  @Nested
  @DisplayName("Tests for complex logic combinations")
  class ComplexLogicTests {

    @Test
    @DisplayName("Should correctly evaluate a mix of class and predicate rules")
    void should_correctly_evaluate_a_mix_of_class_and_predicate_rules() {
      // Given
      FailurePredicateConfigBuilder builder = new FailurePredicateConfigBuilder()
          .recordExceptions(IOException.class)
          .recordWhen(t -> t.getMessage().contains("Critical"))
          .ignoreExceptions(java.io.FileNotFoundException.class);

      FailurePredicateConfig config = builder.build();

      // When / Then
      assertThat(config.finalPredicate().test(new IOException("Standard")))
          .as("Standard IOException should be recorded").isTrue();

      assertThat(config.finalPredicate().test(new RuntimeException("Critical Error")))
          .as("RuntimeException with 'Critical' in message should be recorded").isTrue();

      assertThat(config.finalPredicate().test(new java.io.FileNotFoundException("Missing")))
          .as("FileNotFoundException should be ignored via class exclusion").isFalse();
    }
  }
}