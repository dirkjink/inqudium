package eu.inqudium.core.log;

import java.util.function.Supplier;

// Small adapter to route the calls directly to the optimized SLF4J methods
record Slf4jInfoAction(org.slf4j.Logger slf4j) implements LogAction {

    @Override
    public boolean isEnabled() {
        return slf4j.isInfoEnabled();
    }

    @Override
    public void log(String message) {
        slf4j.info(message);
    }

    @Override
    public void log(String message, Object arg) {
        slf4j.info(message, arg);
    }

    @Override
    public void log(String message, Object arg1, Object arg2) {
        slf4j.info(message, arg1, arg2);
    }

    @Override
    public void log(String message, Object arg1, Object arg2, Object arg3) {
        slf4j.info(message, arg1, arg2, arg3);
    }

    @Override
    public void log(String message, Supplier<?> argSupplier) {
        // Only resolve the supplier if the level is actually enabled.
        if (slf4j.isInfoEnabled()) {
            slf4j.info(message, argSupplier.get());
        }
    }

    @Override
    public void log(String message, Object... args) {
        slf4j.info(message, args);
    }
}