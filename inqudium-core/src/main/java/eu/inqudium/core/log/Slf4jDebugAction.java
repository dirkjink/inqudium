package eu.inqudium.core.log;

import java.util.function.Supplier;

// Small adapter to route the calls directly to the optimized SLF4J methods
record Slf4jDebugAction(org.slf4j.Logger slf4j) implements LogAction {

    @Override
    public boolean isEnabled() {
        return slf4j.isDebugEnabled();
    }

    @Override
    public void log(String message) {
        slf4j.debug(message);
    }

    @Override
    public void log(String message, Object arg) {
        slf4j.debug(message, arg);
    }

    @Override
    public void log(String message, Object arg1, Object arg2) {
        slf4j.debug(message, arg1, arg2);
    }

    @Override
    public void log(String message, Object arg1, Object arg2, Object arg3) {
        // SLF4J 1.x only has up to 2 args natively, so we pass an array here.
        // However, SLF4J 2.x fluent API can avoid this if you upgrade.
        // For now, it delegates to the varargs method inside SLF4J.
        slf4j.debug(message, arg1, arg2, arg3);
    }

    @Override
    public void log(String message, Supplier<?> argSupplier) {
        // Only resolve the supplier if the level is actually enabled.
        if (slf4j.isDebugEnabled()) {
            slf4j.debug(message, argSupplier.get());
        }
    }

    @Override
    public void log(String message, Object... args) {
        slf4j.debug(message, args);
    }
}
