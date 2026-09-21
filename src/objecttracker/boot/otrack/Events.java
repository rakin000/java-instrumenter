package otrack;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-object event trace (JSONL). Callers format a line and {@link #emit} it; one daemon thread writes.
 * The queue is bounded: when the writer cannot keep up, events are dropped and counted, never blocking
 * application threads.
 */
final class Events implements Runnable {
    static volatile boolean on;
    private static volatile Events cur;
    private static final LongAdder dropped = new LongAdder();

    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1 << 16);
    private final BufferedWriter w;
    private final Thread thread = new Thread(this, "otrack-events");
    private volatile boolean running = true;

    private Events(Path out) throws IOException {
        w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        thread.setDaemon(true);
    }

    static synchronized void start(Path out, String fieldsDesc) throws IOException {
        if (cur != null) return;
        Events e = new Events(out);
        dropped.reset();
        long uptimeMs = (System.nanoTime() - Tracker.T0) / 1_000_000L;
        // "t" in every event is microseconds since T0; epochMs below is the wall-clock time of T0.
        e.w.write("{\"ev\":\"start\",\"epochMs\":" + (System.currentTimeMillis() - uptimeMs)
                + ",\"pid\":" + ProcessHandle.current().pid()
                + ",\"fields\":" + Reporter.str(fieldsDesc) + "}\n");
        e.w.flush();
        cur = e;
        e.thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(Events::stop, "otrack-events-flush"));
        on = true;
    }

    static synchronized void stop() {
        Events e = cur;
        if (e == null) return;
        on = false;
        cur = null;
        e.running = false;
        e.thread.interrupt();
        try {
            e.thread.join(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    static long dropped() {
        return dropped.sum();
    }

    static void emit(String line) {
        Events e = cur;
        if (e == null || !e.queue.offer(line)) dropped.increment();
    }

    @Override
    public void run() {
        List<String> batch = new ArrayList<>(4096);
        try {
            while (running) {
                String first;
                try {
                    first = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    break;
                }
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, 8191);
                write(batch);
            }
            queue.drainTo(batch);
            write(batch);
            w.write("{\"ev\":\"end\",\"dropped\":" + dropped.sum() + "}\n");
        } catch (IOException e) {
            Controller.log("cannot write events: " + e);
        } finally {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void write(List<String> batch) throws IOException {
        for (String s : batch) {
            w.write(s);
            w.write('\n');
        }
        batch.clear();
        w.flush();
    }
}
