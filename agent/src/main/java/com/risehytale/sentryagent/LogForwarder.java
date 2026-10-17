package com.risehytale.sentryagent;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryLogLevel;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.logger.SentryLogParameters;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.Message;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

/**
 * Sends every record the server's root logger writes to Sentry. It poses as one of the lists
 * {@code HytaleLoggerBackend.subscribe} takes, the only public hook every record passes through:
 * the server's own Sentry handler sees only records with a throwable, and none from a third-party
 * plugin, whose logger is set not to propagate to it.
 */
final class LogForwarder extends CopyOnWriteArrayList<LogRecord> {

    private static final String UNCAUGHT = "com.hypixel.hytale.logger.backend.HytaleUncaughtExceptionHandler";
    private static final String SKIP_SENTRY = "com.hypixel.hytale.logger.sentry.SkipSentryException";
    // The line RiseContainerCreator waits for before it calls a container booted.
    static final String BOOT_MARKER = "Hytale Server Booted!";
    // The server colours some messages itself, inside the text.
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    // Code that both captures or logs an exception and prints it, as LateMain does with a failed
    // start, would make two issues of one failure; the printed copy is skipped. By Throwable.toString,
    // which is the printed header line.
    private static final long CAPTURED_NANOS = 10_000_000_000L;
    private static final Map<String, Long> captured = new LinkedHashMap<>();

    private final Level logsLevel;
    private final Map<String, Object> attributes;
    private final StreamJoiner printed = new StreamJoiner(this::forwardPrinted);
    private volatile boolean booted;

    LogForwarder(Level logsLevel, Map<String, Object> attributes) {
        this.logsLevel = logsLevel;
        this.attributes = attributes;
    }

    @Override
    public boolean add(LogRecord record) {
        // Runs inside the server's logging call, so nothing may escape into it.
        try {
            forward(record);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Sends the printed lines still waiting for the rest of their stack trace. */
    void flushPrinted(boolean all) {
        try {
            if (all) {
                printed.flushAll();
            } else {
                printed.flushQuiet(System.nanoTime());
            }
        } catch (Throwable ignored) {
        }
    }

    private void forward(LogRecord record) {
        String logger = record.getLoggerName();
        String message = record.getMessage() == null ? "" : ANSI.matcher(record.getMessage()).replaceAll("");
        if (isStdStream(logger)) {
            printed.offer(logger, record.getLevel(), record.getInstant(), message, System.nanoTime());
            return;
        }
        int level = record.getLevel().intValue();
        boolean noise = Noise.isNoise(logger, message);
        if (toLogs(level, logger, message)) {
            log(level, logger, message);
        }
        if (level >= Level.SEVERE.intValue() && !skipped(record.getThrown())) {
            capture(record, message);
        } else if (!noise) {
            breadcrumb(record.getInstant(), level, logger, message);
        }
    }

    // A stack trace printed to the console is the only way some code reports a failure, so it is
    // an issue like a logged exception; any other printed line is only a log line.
    private void forwardPrinted(StreamJoiner.Block block) {
        int level = block.level().intValue();
        String text = String.join("\n", block.lines());
        if (toLogs(level, block.logger(), text)) {
            log(level, block.logger(), text);
        }
        if (block.isTrace() && !recentlyCaptured(block.lines().getFirst().strip())) {
            Sentry.captureEvent(PrintedTrace.event(block.lines(), Date.from(block.instant()), block.logger()), new Hint());
        } else if (!Noise.isNoise(block.logger(), text)) {
            breadcrumb(block.instant(), level, block.logger(), text);
        }
    }

    static void noteCaptured(Throwable thrown) {
        synchronized (captured) {
            captured.put(thrown.toString(), System.nanoTime());
            if (captured.size() > 32) {
                captured.remove(captured.keySet().iterator().next());
            }
        }
    }

    static boolean recentlyCaptured(String header) {
        synchronized (captured) {
            Long at = captured.get(header);
            return at != null && System.nanoTime() - at < CAPTURED_NANOS;
        }
    }

    /**
     * Whether a line goes to Sentry Logs: at or above the meta's level, not {@link Noise}, and, while
     * the server boots, only SEVERE. Boot lines are the same every time; a failing boot still has
     * them as breadcrumbs on its issue.
     */
    boolean toLogs(int level, String logger, String message) {
        if (!booted && message.contains(BOOT_MARKER)) {
            booted = true;
            return level >= logsLevel.intValue();
        }
        if (level < logsLevel.intValue() || (!booted && level < Level.SEVERE.intValue())) {
            return false;
        }
        return !Noise.isNoise(logger, message);
    }

    private void log(int level, String logger, String message) {
        SentryAttributes attrs = SentryAttributes.fromMap(attributes);
        attrs.add(SentryAttribute.stringAttribute("logger", logger));
        Sentry.logger().log(logLevel(level), SentryLogParameters.create(attrs), message);
    }

    private static void breadcrumb(Instant at, int level, String logger, String message) {
        if (level < Level.INFO.intValue()) {
            return;
        }
        Breadcrumb crumb = new Breadcrumb(Date.from(at));
        crumb.setCategory(logger);
        crumb.setLevel(level >= Level.SEVERE.intValue() ? SentryLevel.ERROR
                : level >= Level.WARNING.intValue() ? SentryLevel.WARNING : SentryLevel.INFO);
        crumb.setMessage(message);
        Sentry.addBreadcrumb(crumb);
    }

    private void capture(LogRecord record, String message) {
        boolean uncaught = UNCAUGHT.equals(record.getSourceClassName());
        SentryEvent event = new SentryEvent(Date.from(record.getInstant()));
        event.setLevel(uncaught ? SentryLevel.FATAL : SentryLevel.ERROR);
        event.setLogger(record.getLoggerName());
        Message sentryMessage = new Message();
        sentryMessage.setMessage(message);
        event.setMessage(sentryMessage);
        if (record.getThrown() != null) {
            Mechanism mechanism = new Mechanism();
            mechanism.setType(uncaught ? "UncaughtExceptionHandler" : "HytaleLogger");
            mechanism.setHandled(!uncaught);
            event.setThrowable(new ExceptionMechanismException(mechanism, record.getThrown(), Thread.currentThread()));
        }
        Sentry.captureEvent(event, new Hint());
        if (uncaught) {
            // The thread is dying and may take the JVM with it before the transport gets a turn.
            SentryAgent.flush();
        }
    }

    private static boolean isStdStream(String logger) {
        return "SERR".equals(logger) || "SOUT".equals(logger);
    }

    private static boolean skipped(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getClass().getName().equals(SKIP_SENTRY)) {
                return true;
            }
        }
        return false;
    }

    private static SentryLogLevel logLevel(int level) {
        if (level >= Level.SEVERE.intValue()) {
            return SentryLogLevel.ERROR;
        }
        if (level >= Level.WARNING.intValue()) {
            return SentryLogLevel.WARN;
        }
        if (level >= Level.INFO.intValue()) {
            return SentryLogLevel.INFO;
        }
        return level >= Level.FINE.intValue() ? SentryLogLevel.DEBUG : SentryLogLevel.TRACE;
    }

    // subscribe() skips a list it already contains, and by list equality any empty list would match.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
