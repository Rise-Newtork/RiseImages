package com.risehytale.sentryagent;

import java.util.List;

/**
 * Server lines kept out of Sentry Logs because every server prints them the same way and they say
 * nothing about this one. They still become breadcrumbs. Keep this to lines that never help find a
 * problem; when unsure, leave a line in.
 */
final class Noise {

    private record Rule(String logger, String prefix) {
    }

    private static final List<Rule> RULES = List.of(
            // RawJsonReader growing its buffer: a debug println left in the server, 12 to 14 per boot.
            new Rule("SERR", "Reallocate: "),
            // One per core plugin on every shutdown, about 110. A mod's own plugin still shows.
            new Rule("PluginManager", "Shut down plugin Hytale:"),
            // The rule drawn under the boot banner.
            new Rule("HytaleServer", "====="));

    private Noise() {
    }

    static boolean isNoise(String logger, String message) {
        for (Rule rule : RULES) {
            if (rule.logger.equals(logger) && message.startsWith(rule.prefix)) {
                return true;
            }
        }
        return false;
    }
}
