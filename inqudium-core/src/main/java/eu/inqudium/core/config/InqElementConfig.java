package eu.inqudium.core.config;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public interface InqElementConfig {
  String name();

  InqElementType elementType();

  InqEventPublisher eventPublisher();

  Boolean enableExceptionOptimization();
}
