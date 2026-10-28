package com.risehytale.sentryagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class LogForwarderTest {

    // As LoggerPrintStream hands them over: one record per line, no line ends.
    private static List<String> printed(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return List.of(out.toString().split("\\R"));
    }

    private static Throwable failure(String message) {
        IllegalStateException e = new IllegalStateException(message, new IOException("disk gone"));
        e.addSuppressed(new RuntimeException("while closing"));
        return e;
    }

    private static Throwable elsewhere() {
        return new IllegalStateException("same type, other site");
    }

    @Test
    void aPrintedTraceBecomesOneEventWithItsCauseChain() {
        SentryEvent event = PrintedTrace.event(printed(failure("boom")), new Date(), "SERR");

        List<SentryException> chain = event.getExceptions();
        assertEquals(2, chain.size());
        SentryException outer = chain.get(1);
        assertEquals("IllegalStateException", outer.getType());
        assertEquals("java.lang", outer.getModule());
        assertEquals("boom", outer.getValue());
        assertEquals("printStackTrace", outer.getMechanism().getType());
        assertEquals("IOException", chain.get(0).getType());
        assertEquals("disk gone", chain.get(0).getValue());

        List<SentryStackFrame> frames = outer.getStacktrace().getFrames();
        SentryStackFrame thrower = frames.getLast();
        assertEquals(LogForwarderTest.class.getName(), thrower.getModule());
        assertEquals("failure", thrower.getFunction());
        assertEquals("LogForwarderTest.java", thrower.getFilename());
        assertTrue(thrower.getLineno() > 0);
        assertTrue(thrower.isInApp());
    }

    @Test
    void theSameTraceGroupsAndAnotherSiteDoesNot() {
        List<String> first = PrintedTrace.event(printed(failure("one")), new Date(), "SERR").getFingerprints();
        List<String> again = PrintedTrace.event(printed(failure("two")), new Date(), "SERR").getFingerprints();
        List<String> other = PrintedTrace.event(printed(elsewhere()), new Date(), "SERR").getFingerprints();
        assertEquals(List.of("printed", "java.lang.IllegalStateException", LogForwarderTest.class.getName() + ".failure"), first);
        assertEquals(first, again);
        assertNotEquals(first, other);
    }

    @Test
    void headersParseWithAndWithoutAMessage() {
        SentryEvent bare = PrintedTrace.event(List.of("java.lang.NullPointerException", "\tat a.B.c(B.java:3)"), new Date(), "SERR");
        assertEquals("NullPointerException", bare.getExceptions().getFirst().getType());
        assertEquals(null, bare.getExceptions().getFirst().getValue());

        SentryEvent thread = PrintedTrace.event(List.of("Exception in thread \"main\" a.b.Oops: x: y",
                "\tat java.base/java.lang.Thread.run(Thread.java:1583)", "\tat app//a.B.c(Native Method)"), new Date(), "SERR");
        SentryException oops = thread.getExceptions().getFirst();
        assertEquals("Oops", oops.getType());
        assertEquals("a.b", oops.getModule());
        assertEquals("x: y", oops.getValue());
        assertEquals("java.lang.Thread", oops.getStacktrace().getFrames().getLast().getModule());
        assertTrue(oops.getStacktrace().getFrames().getFirst().isNative());
        SentryEvent mod = PrintedTrace.event(List.of("java.lang.IllegalStateException: x",
                "\tat ThirdParty(com.risehytale:NoiseVerify)//com.risehytale.noiseverify.NoisePlugin.lambda$start$0(NoisePlugin.java:21)"),
                new Date(), "SERR");
        SentryStackFrame modFrame = mod.getExceptions().getFirst().getStacktrace().getFrames().getFirst();
        assertEquals("com.risehytale.noiseverify.NoisePlugin", modFrame.getModule());
        assertEquals("lambda$start$0", modFrame.getFunction());
        assertEquals(21, modFrame.getLineno());
        assertTrue(modFrame.isInApp());
        // No frame of ours: the first frame outside the JDK names the site.
        assertEquals(List.of("printed", "a.b.Oops", "a.B.c"), thread.getFingerprints());
    }

    @Test
    void continuationLines() {
        assertTrue(PrintedTrace.isContinuation("\tat a.B.c(B.java:1)"));
        assertTrue(PrintedTrace.isContinuation("Caused by: java.io.IOException: x"));
        assertTrue(PrintedTrace.isContinuation("\t... 12 more"));
        assertTrue(PrintedTrace.isContinuation("\tSuppressed: java.lang.RuntimeException"));
        assertTrue(PrintedTrace.isContinuation("\t\tat a.B.c(B.java:1)"));
        assertFalse(PrintedTrace.isContinuation("java.lang.IllegalStateException: boom"));
        assertFalse(PrintedTrace.isContinuation("at the moment nothing is wrong"));
        assertFalse(PrintedTrace.isContinuation("Reallocate: 131072 to 1179648"));
    }

    @Test
    void printedLinesJoinPerStream() {
        List<StreamJoiner.Block> out = new ArrayList<>();
        StreamJoiner joiner = new StreamJoiner(out::add);
        Instant now = Instant.now();
        List<String> trace = printed(failure("boom"));

        joiner.offer("SERR", Level.SEVERE, now, "Reallocate: 1 to 2", 0);
        for (String line : trace) {
            joiner.offer("SERR", Level.SEVERE, now, line, 1);
            joiner.offer("SOUT", Level.INFO, now, "unrelated", 1);
        }
        joiner.offer("SERR", Level.SEVERE, now, "next line", 2);

        List<StreamJoiner.Block> serr = out.stream().filter(b -> b.logger().equals("SERR")).toList();
        assertEquals(2, serr.size());
        assertFalse(serr.get(0).isTrace());
        assertEquals(trace, serr.get(1).lines());
        assertTrue(serr.get(1).isTrace());

        out.clear();
        joiner.flushQuiet(StreamJoiner.QUIET_NANOS);
        assertEquals(0, out.size());
        joiner.flushQuiet(1 + StreamJoiner.QUIET_NANOS);
        assertEquals(List.of(List.of("unrelated")), out.stream().map(StreamJoiner.Block::lines).toList());
        joiner.flushQuiet(2 + StreamJoiner.QUIET_NANOS);
        assertEquals(List.of("next line"), out.getLast().lines());
    }

    // As the server printed a config it could not read, with a codec exception's message over several lines.
    private static final List<String> CONFIG_FAILURE = List.of(
            "java.lang.RuntimeException: Failed to read server config!",
            "\tat com.hypixel.hytale.server.core.HytaleServerConfig.load(HytaleServerConfig.java:955)",
            "\tat com.hypixel.hytale.LateMain.lateMain(LateMain.java:62)",
            "Caused by: com.hypixel.hytale.codec.exception.CodecException: Failed to decode 'ServerName' ",
            "From: Streamed RawJsonReader: Index: 37, StreamIndex: 37, BufferIndex: 0, BufferSize: 0, Line: 0, MarkIndex: -1, MarkLine: -1",
            "L  0>",
            "    |^",
            "'",
            "\tat com.hypixel.hytale.codec.builder.BuilderCodec.readField(BuilderCodec.java:423)",
            "\t... 4 more",
            "Caused by: java.io.IOException: Unexpected EOF!",
            "\tat com.hypixel.hytale.codec.util.RawJsonReader.unexpectedEOF(RawJsonReader.java:1021)",
            "\t... 10 more");

    @Test
    void aCauseWithAMessageOverSeveralLinesStaysInOneTrace() {
        List<StreamJoiner.Block> out = new ArrayList<>();
        StreamJoiner joiner = new StreamJoiner(out::add);
        for (String line : CONFIG_FAILURE) {
            joiner.offer("SERR", Level.SEVERE, Instant.now(), line, 0);
        }
        joiner.offer("SERR", Level.SEVERE, Instant.now(), "after", 0);
        joiner.flushAll();
        assertEquals(List.of(CONFIG_FAILURE, List.of("after")), out.stream().map(StreamJoiner.Block::lines).toList());

        List<SentryException> chain = PrintedTrace.event(CONFIG_FAILURE, new Date(), "SERR").getExceptions();
        assertEquals(List.of("IOException", "CodecException", "RuntimeException"), chain.stream().map(SentryException::getType).toList());
        assertTrue(chain.get(1).getValue().startsWith("Failed to decode 'ServerName'\nFrom: Streamed RawJsonReader"));
        assertEquals("com.hypixel.hytale.codec.builder.BuilderCodec", chain.get(1).getStacktrace().getFrames().getFirst().getModule());
    }

    @Test
    void aBlockThatDoesNotStartWithAnExceptionIsNotATrace() {
        assertFalse(new StreamJoiner.Block("SERR", Level.SEVERE, Instant.now(), List.of("    |^", "\tat a.B.c(B.java:1)")).isTrace());
        assertFalse(new StreamJoiner.Block("SERR", Level.SEVERE, Instant.now(), List.of("java.lang.Oops")).isTrace());
        assertTrue(new StreamJoiner.Block("SERR", Level.SEVERE, Instant.now(), List.of("java.lang.Oops", "\t... 1 more")).isTrace());
    }

    @Test
    void anExceptionAlreadyCapturedIsNotCapturedAgainWhenPrinted() {
        Throwable thrown = new IllegalStateException("captured then printed " + System.nanoTime());
        assertFalse(LogForwarder.recentlyCaptured(thrown.toString()));
        LogForwarder.noteCaptured(thrown);
        assertTrue(LogForwarder.recentlyCaptured(printed(thrown).getFirst()));
    }

    @Test
    void anOrphanedContinuationIsNotATrace() {
        List<StreamJoiner.Block> out = new ArrayList<>();
        StreamJoiner joiner = new StreamJoiner(out::add);
        joiner.offer("SERR", Level.SEVERE, Instant.now(), "\tat a.B.c(B.java:1)", 0);
        joiner.offer("SERR", Level.SEVERE, Instant.now(), "\tat a.B.d(B.java:2)", 0);
        joiner.flushAll();
        assertEquals(1, out.size());
        assertFalse(out.getFirst().isTrace());
    }

    @Test
    void bootKeepsOnlySevereOutOfLogs() {
        LogForwarder forwarder = new LogForwarder(Level.INFO, Map.of());
        int info = Level.INFO.intValue();
        int warning = Level.WARNING.intValue();
        int severe = Level.SEVERE.intValue();

        assertFalse(forwarder.toLogs(info, "HytaleServer", "Starting HytaleServer"));
        assertFalse(forwarder.toLogs(warning, "UpdateModule", "Update checker disabled"));
        assertTrue(forwarder.toLogs(severe, "Universe|P", "failed to load world"));
        assertTrue(forwarder.toLogs(info, "HytaleServer", "         Hytale Server Booted! [Multiplayer, Fresh Universe] took 5sec"));
        assertTrue(forwarder.toLogs(info, "HytaleServer", "Shutdown triggered!!!"));
        assertTrue(forwarder.toLogs(warning, "HytaleServer", "No server tokens configured. Use /auth login to authenticate."));
    }

    @Test
    void theMetaLevelStillApplies() {
        LogForwarder forwarder = new LogForwarder(Level.WARNING, Map.of());
        assertFalse(forwarder.toLogs(Level.INFO.intValue(), "HytaleServer", "Hytale Server Booted! took 5sec"));
        assertFalse(forwarder.toLogs(Level.INFO.intValue(), "HytaleServer", "Shutdown triggered!!!"));
        assertTrue(forwarder.toLogs(Level.WARNING.intValue(), "SentryVerify|P", "a warning"));
    }

    @Test
    void noiseStaysOutOfLogsAfterBoot() {
        LogForwarder forwarder = new LogForwarder(Level.INFO, Map.of());
        forwarder.toLogs(Level.INFO.intValue(), "HytaleServer", "Hytale Server Booted!");
        assertFalse(forwarder.toLogs(Level.SEVERE.intValue(), "SERR", "Reallocate: 131072 to 1179648"));
        assertFalse(forwarder.toLogs(Level.INFO.intValue(), "PluginManager", "Shut down plugin Hytale:Weather"));
        assertTrue(forwarder.toLogs(Level.INFO.intValue(), "PluginManager", "Shut down plugin Rise:Lobby"));
        assertFalse(forwarder.toLogs(Level.INFO.intValue(), "HytaleServer", "====================="));
        assertTrue(forwarder.toLogs(Level.SEVERE.intValue(), "SERR", "Reallocate failed for good"));
    }

    @Test
    void onlyAWorldNamedByAMintedIdIsAMatch() {
        assertEquals("7M2H", LogForwarder.matchId("WorldThread - 7M2H"));
        assertEquals("10000", LogForwarder.matchId("WorldThread - 10000"));
        assertNull(LogForwarder.matchId("WorldThread - default"));
        assertNull(LogForwarder.matchId("WorldThread - 3f2a6c1e-8b4d-4e0a-9c7f-1d2e3f4a5b6c"));
        assertNull(LogForwarder.matchId("WorldThread - 7M2"));
        assertNull(LogForwarder.matchId("WorldThread - 7MUH"));
        assertNull(LogForwarder.matchId("WorldThread - instance-sumo-3f2a6c1e"));
        assertNull(LogForwarder.matchId("7M2H"));
        assertNull(LogForwarder.matchId("ShutdownHook"));
    }
}
