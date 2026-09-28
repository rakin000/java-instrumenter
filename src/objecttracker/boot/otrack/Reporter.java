package otrack;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.stream.JsonWriter;

/** One daemon thread: drains the GC reference queue and appends a JSON line per interval. */
final class Reporter implements Runnable {
    private final Path out;
    private final long intervalMs;
    private volatile boolean running = true;
    private final Thread thread = new Thread(this, "otrack-reporter");
    private BufferedWriter w; // opened once at construction, like Events.java, so later writes from this
                               // daemon thread aren't subject to a fresh (and possibly denied) permission check

    Reporter(Path out, long intervalMs) {
        this.out = out;
        this.intervalMs = intervalMs;
        try {
            w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.log("cannot open " + out + ": " + e);
            w = null;
        }
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
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
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
                // Never let one bad tick (sweep or write) kill this thread for the rest of the run: it is
                // the only thing driving both periodic catch-up retransformation and stats reporting.
                try {
                    int caught = Controller.sweepMissed();
                    if (caught > 0) Log.log("sweep instrumented " + caught + " previously-missed loaded classes");
                    write("interval");
                } catch (Throwable t) {
                    Log.log("interval tick failed: " + t);
                }
                next += intervalMs * 1_000_000L;
            }
        }
    }

    synchronized void write(String reason) {
        if (w == null) return; // open already failed and was logged; do not retry every tick
        try {
            w.write(snapshot(reason));
            w.write('\n');
            w.flush();
        } catch (Throwable e) {
            Log.log("cannot write " + out + ": " + e);
        }
    }

    static String snapshot(String reason) throws IOException {
        long nowRel = (System.nanoTime() - Tracker.T0) / 1_000_000L;
        List<Tracker.ClassStats> stats = new ArrayList<>(Tracker.snapshotStats());
        stats.sort(Comparator.comparingLong((Tracker.ClassStats s) -> s.live.sum()).reversed());

        StringWriter out = new StringWriter(1024);
        try (JsonWriter w = new JsonWriter(out)) {
            w.beginObject()
             .name("ts").value(Instant.now().toString())
             .name("reason").value(reason)
             .name("uptimeMs").value(nowRel)
             .name("sample").value(Tracker.sample)
             .name("trackedRefs").value(Tracker.LIVE.size())
             .name("errors").value(Tracker.errors.sum())
             .name("eventsDropped").value(Events.dropped())
             .name("classes").beginArray();
            for (Tracker.ClassStats s : stats) {
                long live = Math.max(0, s.live.sum());
                w.beginObject()
                 .name("class").value(s.name)
                 .name("allocated").value(s.allocated.sum())
                 .name("freed").value(s.freed.sum())
                 .name("live").value(live)
                 .name("shallowBytes").value(s.shallowSize)
                 .name("liveBytes").value(live * s.shallowSize)
                 .name("meanAgeMs").value(live == 0 ? 0 : nowRel - s.birthSumMs.sum() / live);
                if (Tracker.stacks) writeSites(w, s);
                w.endObject();
            }
            w.endArray().endObject();
        }
        return out.toString();
    }

    /** The 10 allocation sites with the most live objects. */
    private static void writeSites(JsonWriter w, Tracker.ClassStats s) throws IOException {
        List<Tracker.Site> sites = new ArrayList<>(s.sites.values());
        sites.removeIf(x -> x.live.sum() <= 0);
        sites.sort(Comparator.comparingLong((Tracker.Site x) -> x.live.sum()).reversed());
        w.name("sites").beginArray();
        for (Tracker.Site x : sites.subList(0, Math.min(10, sites.size()))) {
            w.beginObject().name("live").value(x.live.sum()).name("stack").beginArray();
            for (String frame : x.frames) w.value(frame);
            w.endArray().endObject();
        }
        w.endArray();
    }
}
