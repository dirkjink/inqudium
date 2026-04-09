package eu.inqudium.core.element.trafficshaper.dsl;

public final class Resilience {

  private Resilience() {
  }

  // --- Traffic Shaper (NEU) ---
  public static TrafficShaperProtection shapeWithTrafficShaper() {
    return new DefaultTrafficShaperProtection();
  }
}
