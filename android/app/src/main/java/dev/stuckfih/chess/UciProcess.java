package dev.stuckfih.chess;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A child process that speaks line-based UCI over stdin/stdout. */
public final class UciProcess {
    public interface LineListener {
        /** Called on the reader thread for every line the engine prints. */
        void onLine(String line);

        /** Called once, on the reader thread, when the engine's output stream ends. */
        void onClosed();
    }

    private final Process process;
    private final BufferedWriter writer;
    private volatile boolean closed;

    private UciProcess(Process process) {
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static UciProcess start(List<String> command, final LineListener listener) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        final UciProcess u = new UciProcess(pb.start());
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(u.process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) listener.onLine(line);
                } catch (IOException ignored) {
                    // stream closed: treated as the end of the engine below
                }
                u.closed = true;
                listener.onClosed();
            }
        }, "uci-reader");
        reader.setDaemon(true);
        reader.start();
        return u;
    }

    /** Sends one command line. Returns false when the engine is gone. */
    public synchronized boolean send(String line) {
        if (closed) return false;
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
            return true;
        } catch (IOException e) {
            closed = true;
            return false;
        }
    }

    public boolean isAlive() {
        if (closed) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /** Asks the engine to quit and makes sure the process is gone shortly afterwards. */
    public void quit() {
        send("quit");
        Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    // fall through and destroy
                }
                process.destroy();
            }
        }, "uci-killer");
        killer.setDaemon(true);
        killer.start();
    }
}
