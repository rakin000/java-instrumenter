package otrack;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** The agent's own {@code [otrack]} messages: to stderr, or appended to the file given by {@code logFile=}. */
final class Log {
    private static volatile Writer file;

    private Log() {}

    /**
     * Opens the log file once, up front, and keeps it open for the run: like Events.java, this dodges hosts
     * (e.g. Elasticsearch's entitlements) that grant file access at open time to whatever thread/context is
     * live during agent start-up but deny a fresh permission check made later from a background thread.
     */
    static synchronized void openFile(Path path) {
        if (file != null) return;
        try {
            file = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable e) {
            System.err.println("[otrack] cannot open log file " + path + ": " + e);
        }
    }

    static void log(String msg) {
        Writer w = file;
        if (w != null) {
            try {
                synchronized (Log.class) {
                    w.write(Instant.now() + " [otrack] " + msg + System.lineSeparator());
                    w.flush();
                }
                return;
            } catch (Throwable e) {
                // Catches more than IOException on purpose: under a SecurityManager (e.g. Elasticsearch's
                // bootstrap entitlements), a write from a thread not covered by the host's own grants throws
                // AccessControlException/SecurityException, not IOException. This method must never propagate
                // an exception, since callers include the otrack-reporter daemon thread, whose sole job is the
                // periodic instrumentation sweep in Controller.sweepMissed() -- letting a logging failure kill
                // that thread silently disables all further catch-up retransformation for the rest of the run.
                System.err.println("[otrack] cannot write log file: " + e);
                file = null; // stop retrying every call for the rest of the run; fall back to stderr
            }
        }
        System.err.println("[otrack] " + msg);
    }
}
