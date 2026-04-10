package eu.inqudium.core.log;

import java.util.function.Supplier;

public interface LogAction {

    // Guard to completely bypass expensive log construction blocks
    boolean isEnabled();

    // Optimization for exact arguments to prevent Object[] array creation
    void log(String message);

    void log(String message, Object arg);

    void log(String message, Object arg1, Object arg2);

    void log(String message, Object arg1, Object arg2, Object arg3);

    // Lazy evaluation: The supplier is only executed if the log level is enabled.
    // Prevents object allocation and CPU cycles for disabled log levels.
    void log(String message, Supplier<?> argSupplier);

    // Fallback for 4 or more arguments (incurs array allocation)
    void log(String message, Object... args);
}

