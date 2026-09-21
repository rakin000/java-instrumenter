package otrack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One daemon thread: drains the GC reference queue and appends a JSON line per interval. */
final class Reporter implements Runnable {
    private final Path out;
    private final long intervalMs;
    private volatile boolean running = true;
    private final Thread thread = new Thread(this, "otrack-reporter");

    Reporter(Path out, long intervalMs) {
        this.out = out;
        this.intervalMs = intervalMs;
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        write("final");
    }

    @Override
    public void run() {
        long next = System.nanoTime() + intervalMs * 1_000_000L;
        while (running) {
            try {
                Tracker.Ref r = (Tracker.Ref) Tracker.QUEUE.remove(250);
                while (r != null) {
                    Tracker.release(r);
                    r = (Tracker.Ref) Tracker.QUEUE.poll();
                }
            } catch (InterruptedException e) {
                return;
            }
            if (System.nanoTime() - next >= 0) {
                write("interval");
                next += intervalMs * 1_000_000L;
            }
        }
    }

    synchronized void write(String reason) {
        try {
            Files.writeString(out, snapshot(reason) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Controller.log("cannot write " + out + ": " + e);
        }
    }

    static String snapshot(String reason) {
        long nowRel = (System.nanoTime() - Tracker.T0) / 1_000_000L;
        List<Tracker.ClassStats> stats = new ArrayList<>(Tracker.snapshotStats());
        stats.sort(Comparator.comparingLong((Tracker.ClassStats s) -> s.live.sum()).reversed());

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ts\":\"").append(Instant.now()).append("\",\"reason\":\"").append(reason)
          .append("\",\"uptimeMs\":").append(nowRel)
          .append(",\"sample\":").append(Tracker.sample)
          .append(",\"trackedRefs\":").append(Tracker.LIVE.size())
          .append(",\"errors\":").append(Tracker.errors.sum())
          .append(",\"eventsDropped\":").append(Events.dropped())
          .append(",\"classes\":[");
        boolean first = true;
        for (Tracker.ClassStats s : stats) {
            long live = Math.max(0, s.live.sum());
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"class\":").append(str(s.name))
              .append(",\"allocated\":").append(s.allocated.sum())
              .append(",\"freed\":").append(s.freed.sum())
              .append(",\"live\":").append(live)
              .append(",\"shallowBytes\":").append(s.shallowSize)
              .append(",\"liveBytes\":").append(live * s.shallowSize)
              .append(",\"meanAgeMs\":").append(live == 0 ? 0 : nowRel - s.birthSumMs.sum() / live);
            if (Tracker.stacks) {
                List<Tracker.Site> sites = new ArrayList<>(s.sites.values());
                sites.removeIf(x -> x.live.sum() <= 0);
                sites.sort(Comparator.comparingLong((Tracker.Site x) -> x.live.sum()).reversed());
                sb.append(",\"sites\":[");
                for (int i = 0; i < Math.min(10, sites.size()); i++) {
                    if (i > 0) sb.append(',');
                    Tracker.Site x = sites.get(i);
                    sb.append("{\"live\":").append(x.live.sum()).append(",\"stack\":[");
                    for (int j = 0; j < x.frames.length; j++) {
                        if (j > 0) sb.append(',');
                        sb.append(str(x.frames[j]));
                    }
                    sb.append("]}");
                }
                sb.append(']');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
