package com.risehytale.sentryagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Mechanism;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class SentryAgentTest {

    // As RiseContainerCreator's Provisioner.metaJson writes it, through protobuf's JsonFormat.
    private static final String HUB = """
            {"containerName":"hub-1a2b","requestId":"r-9","nodeMeta":{"nodeId":"node-eu-1","nodeLatitude":51.5},
             "serverTypes":["SERVER_TYPE_HUB","SERVER_TYPE_HUB_PVP"],"gamePort":5520,
             "hub":{"type":"HUB_SERVER_TYPE_HUB"},
             "sentry":{"dsn":"https://key@o1.ingest.de.sentry.io/2","environment":"staging","release":"rise-hytale@1.2.0","logsLevel":"WARNING"}}""";

    @Test
    void readsSentryAndTagsFromTheMeta() {
        Meta meta = Meta.parse(HUB);
        assertEquals("https://key@o1.ingest.de.sentry.io/2", meta.dsn());
        assertEquals("staging", meta.environment());
        assertEquals("rise-hytale@1.2.0", meta.release());
        assertEquals("WARNING", meta.logsLevel());
        assertEquals(Map.of("container", "hub-1a2b", "node", "node-eu-1",
                "server_types", "SERVER_TYPE_HUB,SERVER_TYPE_HUB_PVP", "hub_type", "HUB_SERVER_TYPE_HUB"),
                SentryAgent.tags(meta));
    }

    @Test
    void aGameServerHasNoHubType() {
        Meta meta = Meta.parse("""
                {"containerName":"sg-1","serverTypes":["SERVER_TYPE_STICK_GAME"],
                 "game":{"types":["SERVER_TYPE_STICK_GAME"]},"sentry":{"dsn":"https://k@h/1"}}""");
        assertEquals(List.of("container", "server_types"), List.copyOf(SentryAgent.tags(meta).keySet()));
        assertEquals("", meta.environment());
        assertEquals("", meta.logsLevel());
    }

    @Test
    void noSentryBlockOrABlankDsnMeansNoDsn() {
        assertEquals("", Meta.parse("{\"containerName\":\"x\"}").dsn());
        assertEquals("", Meta.parse("{\"sentry\":{\"dsn\":\"  \"}}").dsn());
    }

    @Test
    void logsLevelTakesJulNamesAndTheOtherServicesNames() {
        assertEquals(Level.INFO, SentryAgent.logsLevel(null));
        assertEquals(Level.INFO, SentryAgent.logsLevel(""));
        assertEquals(Level.SEVERE, SentryAgent.logsLevel("SEVERE"));
        assertEquals(Level.WARNING, SentryAgent.logsLevel("WARNING"));
        assertEquals(Level.CONFIG, SentryAgent.logsLevel("CONFIG"));
        assertEquals(Level.FINER, SentryAgent.logsLevel("FINER"));
        assertEquals(Level.ALL, SentryAgent.logsLevel("ALL"));
        assertEquals(Level.OFF, SentryAgent.logsLevel("OFF"));
        assertEquals(Level.WARNING, SentryAgent.logsLevel("warn"));
        assertEquals(Level.SEVERE, SentryAgent.logsLevel("error"));
        assertEquals(Level.FINE, SentryAgent.logsLevel("debug"));
        assertEquals(Level.INFO, SentryAgent.logsLevel("loud"));
    }

    @Test
    void onlyADirectCaptureIsMarkedFatal() {
        SentryEvent direct = new SentryEvent(new IllegalStateException("config"));
        assertEquals(SentryLevel.FATAL, SentryAgent.markStartupFailure(direct).getLevel());

        SentryEvent logged = new SentryEvent();
        logged.setLevel(SentryLevel.ERROR);
        logged.setThrowable(new ExceptionMechanismException(new Mechanism(), new RuntimeException(), Thread.currentThread()));
        assertEquals(SentryLevel.ERROR, SentryAgent.markStartupFailure(logged).getLevel());

        SentryEvent message = new SentryEvent();
        message.setLevel(SentryLevel.ERROR);
        assertEquals(SentryLevel.ERROR, SentryAgent.markStartupFailure(message).getLevel());
    }

    @Test
    void passwordsAreMasked() {
        assertEquals("postgres://rise:***@db:5432/rise", Redaction.redact("postgres://rise:hunter2@db:5432/rise"));
        assertEquals("no secrets here", Redaction.redact("no secrets here"));
    }
}
