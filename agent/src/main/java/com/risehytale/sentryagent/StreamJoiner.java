package com.risehytale.sentryagent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Joins the lines of a stack trace printed to System.out or System.err, which the server logs one
 * record per line, back into one block. A block ends when a line that does not continue it arrives
 * on the same stream, or when the stream has been quiet for {@link #QUIET_NANOS}.
 */
final class StreamJoiner {

    static final long QUIET_NANOS = 200_000_000L;

    record Block(String logger, Level level, Instant instant, List<String> lines) {

        /** A header line and at least one line continuing it, as opposed to a lone printed line. */
        boolean isTrace() {
            return lines.size() > 1 && PrintedTrace.isHeader(lines.getFirst());
        }
    }

    // inCause: the last lines were a "Caused by:" and its message so far. The server's own codec
    // exceptions have messages several lines long, and those lines continue nothing on their own.
    private record Pending(Block block, long lastNanos, boolean inCause) {
    }

    private final Consumer<Block> sink;
    private final Map<String, Pending> pending = new HashMap<>();

    StreamJoiner(Consumer<Block> sink) {
        this.sink = sink;
    }

    void offer(String logger, Level level, Instant instant, String line, long nowNanos) {
        Block done = null;
        synchronized (this) {
            Pending open = pending.get(logger);
            // printStackTrace holds the stream's lock for the whole trace, so its lines arrive together.
            boolean continues = PrintedTrace.isContinuation(line);
            if (open != null && (continues || open.inCause)) {
                open.block.lines().add(line);
                boolean inCause = line.startsWith("Caused by: ") || (open.inCause && !continues);
                pending.put(logger, new Pending(open.block, nowNanos, inCause));
                return;
            }
            if (open != null) {
                done = open.block;
            }
            List<String> lines = new ArrayList<>();
            lines.add(line);
            pending.put(logger, new Pending(new Block(logger, level, instant, lines), nowNanos, false));
        }
        if (done != null) {
            sink.accept(done);
        }
    }

    void flushQuiet(long nowNanos) {
        List<Block> done = new ArrayList<>();
        synchronized (this) {
            pending.values().removeIf(p -> {
                if (nowNanos - p.lastNanos < QUIET_NANOS) {
                    return false;
                }
                done.add(p.block);
                return true;
            });
        }
        done.forEach(sink);
    }

    void flushAll() {
        List<Block> done;
        synchronized (this) {
            done = pending.values().stream().map(Pending::block).toList();
            pending.clear();
        }
        done.forEach(sink);
    }
}
