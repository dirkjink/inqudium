package eu.inqudium.core.element.config;

import eu.inqudium.core.config.ConfigExtension;

import java.util.function.Predicate;

public record FailurePredicateConfig(
    Predicate<Throwable> finalPredicate) implements ConfigExtension<FailurePredicateConfig> {
  @Override
  public FailurePredicateConfig self() {
    return this;
  }
}
