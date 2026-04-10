package eu.inqudium.core.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Slf4jLoggerFactory implements LoggerFactory {

    // Cache to prevent allocating 5 new objects every time getLogger is called
    private final ConcurrentMap<Class<?>, Logger> loggerCache = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(Class<?> clazz) {
        // Compute if absent is thread-safe and highly performant
        return loggerCache.computeIfAbsent(clazz, this::createNewLogger);
    }

    private Logger createNewLogger(Class<?> clazz) {
        org.slf4j.Logger slf4j = org.slf4j.LoggerFactory.getLogger(clazz);
        return new Logger(
                new Slf4jDebugAction(slf4j),
                new Slf4jInfoAction(slf4j),
                new Slf4jWarnAction(slf4j),
                new Slf4jErrorAction(slf4j)
        );
    }
}
