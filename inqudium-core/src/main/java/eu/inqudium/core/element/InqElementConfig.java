package eu.inqudium.core.element;

import eu.inqudium.core.event.InqEventPublisher;

public interface InqElementConfig {
  String name();

  InqElementType elementType();

  InqEventPublisher eventPublisher();
}
