package com.risehytale.sentryagent;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import java.util.regex.Pattern;

/** Masks the password in {@code user:password@host}, which a bad connection URL puts in a message. */
final class Redaction {

    private static final Pattern USERINFO = Pattern.compile("([^\\s/:@]+):([^\\s/@]+)@");

    private Redaction() {
    }

    static String redact(String text) {
        return text == null ? null : USERINFO.matcher(text).replaceAll("$1:***@");
    }

    static SentryEvent event(SentryEvent event, Hint hint) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(redact(message.getMessage()));
            message.setFormatted(redact(message.getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(redact(exception.getValue()));
            }
        }
        if (event.getBreadcrumbs() != null) {
            for (Breadcrumb breadcrumb : event.getBreadcrumbs()) {
                breadcrumb.setMessage(redact(breadcrumb.getMessage()));
            }
        }
        return event;
    }

    static SentryLogEvent log(SentryLogEvent log) {
        log.setBody(redact(log.getBody()));
        return log;
    }
}
