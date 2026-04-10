package eu.inqudium.core.element.trafficshaper.dsl;

public interface TrafficShaperNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    TrafficShaperProtection named(String name);
}
