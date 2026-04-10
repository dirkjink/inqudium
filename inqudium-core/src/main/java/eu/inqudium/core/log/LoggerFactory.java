package eu.inqudium.core.log;

@FunctionalInterface
public interface LoggerFactory {

    LoggerFactory NO_OP_LOGGER_FACTORY = (c) -> Logger.NO_OP_LOGGER;

    Logger getLogger(Class<?> clazz);
}

