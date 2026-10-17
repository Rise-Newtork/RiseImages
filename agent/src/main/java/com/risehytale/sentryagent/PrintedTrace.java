package com.risehytale.sentryagent;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the lines of a stack trace printed to System.out or System.err back into one Sentry event. */
final class PrintedTrace {

    private static final Pattern HEADER = Pattern.compile(
            "(?:Exception in thread \"[^\"]*\" )?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(?::\\s?(.*))?");
    private static final List<String> PLATFORM = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private PrintedTrace() {
    }

    /** Whether a printed line belongs to the stack trace above it rather than starting a new line of output. */
    static boolean isContinuation(String line) {
        int tabs = tabs(line);
        String body = line.substring(tabs).stripLeading();
        if (body.startsWith("Caused by: ")) {
            return true;
        }
        if (tabs == 0 && !line.startsWith(" ")) {
            return false;
        }
        return body.startsWith("at ") || body.startsWith("Suppressed: ")
                || (body.startsWith("... ") && body.endsWith(" more"));
    }

    /** Whether a printed line can start a stack trace: an exception's class name, then maybe its message. */
    static boolean isHeader(String line) {
        return HEADER.matcher(line).matches();
    }

    /**
     * The event for a header line followed by its continuation lines. Only the main cause chain is
     * kept as exceptions; suppressed exceptions stay in the text that goes to Logs.
     */
    static SentryEvent event(List<String> lines, Date timestamp, String logger) {
        List<SentryException> chain = new ArrayList<>();
        List<SentryStackFrame> frames = null;
        boolean inMessage = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int tabs = tabs(line);
            String body = line.substring(tabs);
            if (i == 0 || (tabs == 0 && body.startsWith("Caused by: "))) {
                frames = new ArrayList<>();
                chain.add(exception(i == 0 ? body.strip() : body.substring("Caused by: ".length()).strip(), frames));
                inMessage = true;
            } else if (inMessage && !isContinuation(line)) {
                SentryException current = chain.getLast();
                current.setValue(current.getValue() == null ? line : current.getValue() + "\n" + line);
            } else if (tabs == 1) {
                inMessage = false;
                SentryStackFrame frame = frame(body);
                if (frame != null) {
                    frames.add(frame);
                }
            }
        }

        SentryException outer = chain.getFirst();
        Mechanism mechanism = new Mechanism();
        mechanism.setType("printStackTrace");
        mechanism.setHandled(true);
        outer.setMechanism(mechanism);

        SentryEvent event = new SentryEvent(timestamp);
        event.setLevel(SentryLevel.ERROR);
        event.setLogger(logger);
        event.setFingerprints(fingerprint(outer));
        for (SentryException exception : chain) {
            // Sentry wants frames caller first and the chain innermost cause first: both reversed from print order.
            Collections.reverse(exception.getStacktrace().getFrames());
        }
        Collections.reverse(chain);
        event.setExceptions(chain);
        return event;
    }

    // Without one, Sentry would group on the whole chain, so the same failure reached by two call
    // paths would be two issues. Type plus the frame that threw groups repeats and splits sites.
    private static List<String> fingerprint(SentryException outer) {
        String type = outer.getModule() == null ? outer.getType() : outer.getModule() + "." + outer.getType();
        List<SentryStackFrame> frames = outer.getStacktrace().getFrames();
        SentryStackFrame top = frames.stream().filter(f -> Boolean.TRUE.equals(f.isInApp())).findFirst()
                .or(() -> frames.stream().filter(f -> !platform(f.getModule())).findFirst())
                .or(() -> frames.stream().findFirst())
                .orElse(null);
        return top == null ? List.of("printed", type) : List.of("printed", type, top.getModule() + "." + top.getFunction());
    }

    private static SentryException exception(String header, List<SentryStackFrame> frames) {
        SentryException exception = new SentryException();
        Matcher m = HEADER.matcher(header);
        if (m.matches()) {
            String name = m.group(1);
            int dot = name.lastIndexOf('.');
            exception.setType(name.substring(dot + 1));
            if (dot > 0) {
                exception.setModule(name.substring(0, dot));
            }
            exception.setValue(m.group(2));
        } else {
            exception.setType("PrintedStackTrace");
            exception.setValue(header);
        }
        exception.setStacktrace(new SentryStackTrace(frames));
        return exception;
    }

    // "at [loader/][module@version/]Class.method(Source)". A loader's name can hold brackets of its
    // own (a mod's is "ThirdParty(group:Name)"), so the source is whatever the last bracket opens.
    private static SentryStackFrame frame(String body) {
        int open = body.lastIndexOf('(');
        if (!body.startsWith("at ") || open < 0 || !body.endsWith(")")) {
            return null;
        }
        String qualified = body.substring(body.lastIndexOf('/', open) + 1, open);
        if (qualified.startsWith("at ")) {
            qualified = qualified.substring(3);
        }
        int dot = qualified.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String className = qualified.substring(0, dot);
        SentryStackFrame frame = new SentryStackFrame();
        frame.setModule(className);
        frame.setFunction(qualified.substring(dot + 1));
        frame.setInApp(SentryAgent.IN_APP.stream().anyMatch(className::startsWith));
        String source = body.substring(open + 1, body.length() - 1);
        if (source.equals("Native Method")) {
            frame.setNative(true);
        } else if (!source.equals("Unknown Source")) {
            int colon = source.lastIndexOf(':');
            if (colon > 0) {
                frame.setFilename(source.substring(0, colon));
                try {
                    frame.setLineno(Integer.parseInt(source.substring(colon + 1)));
                } catch (NumberFormatException ignored) {
                }
            } else {
                frame.setFilename(source);
            }
        }
        return frame;
    }

    private static boolean platform(String module) {
        return module != null && PLATFORM.stream().anyMatch(module::startsWith);
    }

    private static int tabs(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '\t') {
            n++;
        }
        return n;
    }
}
