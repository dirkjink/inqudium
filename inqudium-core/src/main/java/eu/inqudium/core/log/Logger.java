package eu.inqudium.core.log;

import java.util.function.Supplier;

/**
 * When four trailing arguments are passed via varargs, this Logger identifies the
 * final argument as a Throwable and logs the full stack trace accordingly.
 * While production behavior remains standard, the test suite captures these
 * within the {@code args[]} array to verify the presence of the exception.
 */
public record Logger(LogAction debug, LogAction info, LogAction warn, LogAction error) {

    // A silent no-operation implementation that costs virtually nothing
    public static final LogAction NO_OP_ACTION = new LogAction() {

        @Override
        public boolean isEnabled() {
            return false; // Crucial for performance: signals that logging is off
        }

        @Override
        public void log(String message) {
        }

        @Override
        public void log(String message, Object arg) {
        }

        @Override
        public void log(String message, Object arg1, Object arg2) {
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3) {
        }

        @Override
        public void log(String message, Supplier<?> argSupplier) {
        }

        @Override
        public void log(String message, Object... args) {
        }
    };

    public static final Logger NO_OP_LOGGER = new Logger(
            NO_OP_ACTION, NO_OP_ACTION, NO_OP_ACTION, NO_OP_ACTION
    );
}

