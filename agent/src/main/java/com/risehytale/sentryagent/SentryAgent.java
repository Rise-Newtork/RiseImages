package com.risehytale.sentryagent;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.exception.ExceptionMechanismException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Reports the Hytale server to the Sentry project named in {@code RISE_SERVER_META}, using the
 * Sentry SDK the server jar bundles. Without a DSN there it does nothing.
 */
public final class SentryAgent {

    static final List<String> IN_APP = List.of("com.risehytale", "com.hypixel.hytale");

    private static volatile LogForwarder forwarder;

    private SentryAgent() {
    }

    public static void premain(String args) {
        String json = System.getenv("RISE_SERVER_META");
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            start(Meta.parse(json));
        } catch (Throwable t) {
            // The server matters more than its reporting, so it starts without Sentry.
            System.err.println("sentry-agent: Sentry is off: " + t);
        }
    }

    static void start(Meta meta) throws ReflectiveOperationException {
        if (meta.dsn().isEmpty()) {
            return;
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        // HytaleLogger's static init installs its JUL LogManager and refuses to run if any other
        // code reached LogManager first. Sentry's HTTP transport would, so HytaleLogger goes first.
        Class.forName("com.hypixel.hytale.logger.HytaleLogger", true, loader);

        Map<String, String> tags = tags(meta);
        Sentry.init(options -> {
            options.setDsn(meta.dsn());
            options.setEnvironment(meta.environment().isEmpty() ? "production" : meta.environment());
            if (!meta.release().isEmpty()) {
                options.setRelease(meta.release());
            }
            if (!meta.container().isEmpty()) {
                options.setServerName(meta.container());
            }
            tags.forEach(options::setTag);
            IN_APP.forEach(options::addInAppInclude);
            options.getLogs().setEnabled(true);
            options.getLogs().setBeforeSend(Redaction::log);
            options.setBeforeSend((event, hint) -> {
                if (event.getThrowable() != null) {
                    LogForwarder.noteCaptured(event.getThrowable());
                }
                return Redaction.event(markStartupFailure(event), hint);
            });
            // HytaleLogger has already taken the default handler slot; it logs each uncaught
            // exception SEVERE, which LogForwarder captures and flushes.
            options.setEnableUncaughtExceptionHandler(false);
            options.setEnableShutdownHook(false);
        }, true);
        Runtime.getRuntime().addShutdownHook(new Thread(SentryAgent::flushAfterServer, "sentry-agent-shutdown"));

        forwarder = new LogForwarder(logsLevel(meta.logsLevel()), Map.copyOf(tags));
        Class.forName("com.hypixel.hytale.logger.backend.HytaleLoggerBackend", true, loader)
                .getMethod("subscribe", CopyOnWriteArrayList.class)
                .invoke(null, forwarder);
        // A printed stack trace's last line is only known once its stream goes quiet.
        Thread quiet = Thread.ofPlatform().daemon().name("sentry-agent-printed").unstarted(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                forwarder.flushPrinted(false);
            }
        });
        quiet.start();
    }

    // The JVM runs shutdown hooks concurrently, and on a signal the server's own hook does its whole
    // shutdown, logging as it goes. Sentry's hook would close the client under it and drop that, so
    // this one waits for the server's. The JVM starts every hook before joining any, so if the
    // server registered one it shows up at once; a server that never got that far has none.
    private static void flushAfterServer() {
        try {
            for (int tries = 0; tries < 50; tries++) {
                Thread hook = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "ShutdownHook".equals(t.getName())).findFirst().orElse(null);
                if (hook != null) {
                    hook.join(30_000);
                    break;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException ignored) {
        }
        flush();
    }

    /**
     * Sends everything queued, logs included, within about 2s. Not close(): that waits out the 5s
     * log batch timer, and the server halts the JVM 3s after its shutdown.
     */
    static void flush() {
        LogForwarder logs = forwarder;
        if (logs != null) {
            logs.flushPrinted(true);
        }
        // One flush sends only whole batches of 100 logs and leaves the rest on that timer, so the
        // first queues a send and the second, run after it, takes the remainder and waits.
        Sentry.flush(0);
        Sentry.flush(2000);
    }

    // LateMain is the only server code that captures straight to Sentry: it reports an exception
    // that stops the server starting, then rethrows, and Sentry drops the uncaught-exception event
    // LogForwarder sends next as a duplicate. So that capture is the one to mark fatal.
    static SentryEvent markStartupFailure(SentryEvent event) {
        Throwable thrown = event.getThrowableMechanism();
        if (thrown != null && !(thrown instanceof ExceptionMechanismException)) {
            event.setLevel(SentryLevel.FATAL);
        }
        return event;
    }

    static Map<String, String> tags(Meta meta) {
        Map<String, String> tags = new LinkedHashMap<>();
        putIfSet(tags, "container", meta.container());
        putIfSet(tags, "node", meta.node());
        putIfSet(tags, "server_types", String.join(",", meta.serverTypes()));
        putIfSet(tags, "hub_type", meta.hubType());
        return tags;
    }

    private static void putIfSet(Map<String, String> tags, String key, String value) {
        if (!value.isEmpty()) {
            tags.put(key, value);
        }
    }

    /**
     * A java.util.logging level name, as the creator writes {@code sentry.logsLevel}; the logback-style
     * names the other services take are accepted too. Blank is INFO.
     */
    static Level logsLevel(String name) {
        if (name == null || name.isBlank()) {
            return Level.INFO;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        try {
            return switch (upper) {
                case "TRACE" -> Level.FINEST;
                case "DEBUG" -> Level.FINE;
                case "WARN" -> Level.WARNING;
                case "ERROR" -> Level.SEVERE;
                default -> Level.parse(upper);
            };
        } catch (IllegalArgumentException e) {
            System.err.println("sentry-agent: sentry.logsLevel '" + name + "' is not a level, using INFO");
            return Level.INFO;
        }
    }
}
