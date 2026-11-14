package dev.stuckfih.chess;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks UCI to an engine process: start-up handshake, options, searches and cancellation.
 *
 * Every search gets a token. A cancelled or superseded search still produces a "bestmove" line
 * from the engine; those are swallowed here so callers only ever see results for the search they
 * asked for last. Callbacks arrive on the engine's reader thread.
 */
public final class EngineSession implements UciProcess.LineListener {

    /** One "info" line of a running search. */
    public static final class SearchInfo {
        public int depth;
        public int selDepth;
        /** True when {@link #score} is "mate in N" (negative: being mated), false for centipawns. */
        public boolean mate;
        /** Score from the point of view of the side to move at the root of the search. */
        public int score;
        public boolean lowerBound;
        public boolean upperBound;
        /** Win/draw/loss in permille from the side to move's view, or -1 when not reported. */
        public int wdlWin = -1;
        public int wdlDraw = -1;
        public int wdlLoss = -1;
        public long nodes;
        public long nps;
        public long timeMs;
        public final List<String> pv = new ArrayList<>();
    }

    public interface Callbacks {
        void onReady(String engineName);

        void onInfo(SearchInfo info, int token);

        /** @param move best move in UCI notation, or null when the engine had no legal move */
        void onBestMove(String move, int token);

        void onFailure(String message);
    }

    private static final class Pending {
        final int token;
        final String fen;
        final List<String> moves;
        final int movetimeMs;

        Pending(int token, String fen, List<String> moves, int movetimeMs) {
            this.token = token;
            this.fen = fen;
            this.moves = moves;
            this.movetimeMs = movetimeMs;
        }
    }

    private final Callbacks callbacks;
    private final Map<String, String> options = new LinkedHashMap<>();
    private final ArrayDeque<Integer> inflight = new ArrayDeque<>();
    private UciProcess proc;
    private boolean ready;
    private boolean quitting;
    private String engineName = "Stockfish";
    private int nextToken = 1;
    private int activeToken = -1;
    private Pending pending;

    public EngineSession(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Launches the engine and begins the UCI handshake; onReady fires when it can search. */
    public synchronized void start(List<String> command, Map<String, String> initialOptions) throws IOException {
        if (proc != null) throw new IllegalStateException("already started");
        options.clear();
        options.putAll(initialOptions);
        quitting = false;
        ready = false;
        inflight.clear();
        pending = null;
        proc = UciProcess.start(command, this);
        proc.send("uci");
    }

    public synchronized boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    public synchronized boolean isReady() {
        return ready && isRunning();
    }

    public synchronized String engineName() {
        return engineName;
    }

    public synchronized void setOption(String name, String value) {
        options.put(name, value);
        if (ready && proc != null) proc.send("setoption name " + name + " value " + value);
    }

    /** Starts a fresh game inside the engine (clears hash and history). */
    public synchronized void newGame() {
        cancelLocked();
        if (ready && proc != null) proc.send("ucinewgame");
    }

    /**
     * Searches the position given by a start FEN plus moves for movetimeMs milliseconds, replacing
     * any running search. Returns the token that will accompany the info and best move callbacks.
     */
    public synchronized int search(String fen, List<String> moves, int movetimeMs) {
        int token = nextToken++;
        activeToken = token;
        pending = null;
        if (proc == null) return token;
        if (!ready) {
            pending = new Pending(token, fen, new ArrayList<>(moves), movetimeMs);
            return token;
        }
        if (!inflight.isEmpty()) proc.send("stop");
        sendSearch(token, fen, moves, movetimeMs);
        return token;
    }

    /** Stops the running search; its best move will be discarded. */
    public synchronized void cancel() {
        cancelLocked();
    }

    private void cancelLocked() {
        activeToken = -1;
        pending = null;
        if (proc != null && !inflight.isEmpty()) proc.send("stop");
    }

    /** Terminates the engine process. The session can be started again afterwards. */
    public synchronized void shutdown() {
        quitting = true;
        activeToken = -1;
        pending = null;
        ready = false;
        inflight.clear();
        if (proc != null) {
            proc.quit();
            proc = null;
        }
    }

    private void sendSearch(int token, String fen, List<String> moves, int movetimeMs) {
        StringBuilder sb = new StringBuilder("position fen ").append(fen);
        if (!moves.isEmpty()) {
            sb.append(" moves");
            for (String m : moves) sb.append(' ').append(m);
        }
        proc.send(sb.toString());
        proc.send("go movetime " + Math.max(10, movetimeMs));
        inflight.addLast(token);
    }

    // ------------------------------------------------------------------ UciProcess.LineListener

    @Override
    public void onLine(String line) {
        if (line.isEmpty()) return;
        String readyName = null;
        SearchInfo info = null;
        int infoToken = -1;
        String best = null;
        boolean deliverBest = false;
        int bestToken = -1;

        synchronized (this) {
            if (quitting) return;
            if (line.startsWith("id name ")) {
                engineName = line.substring(8).trim();
            } else if (line.equals("uciok")) {
                if (proc != null) {
                    for (Map.Entry<String, String> e : options.entrySet()) {
                        proc.send("setoption name " + e.getKey() + " value " + e.getValue());
                    }
                    proc.send("isready");
                }
            } else if (line.equals("readyok")) {
                if (!ready && proc != null) {
                    ready = true;
                    readyName = engineName;
                    if (pending != null) {
                        Pending p = pending;
                        pending = null;
                        sendSearch(p.token, p.fen, p.moves, p.movetimeMs);
                    }
                }
            } else if (line.startsWith("info ")) {
                info = parseInfo(line);
                if (info != null) {
                    Integer head = inflight.peekFirst();
                    if (head != null && head == activeToken) infoToken = head;
                    else info = null;
                }
            } else if (line.startsWith("bestmove")) {
                Integer head = inflight.pollFirst();
                if (head != null && head == activeToken) {
                    String[] t = line.split("\\s+");
                    best = t.length > 1 && !t[1].equals("(none)") && !t[1].equals("0000") ? t[1] : null;
                    deliverBest = true;
                    bestToken = head;
                    activeToken = -1;
                }
            }
        }

        if (readyName != null) callbacks.onReady(readyName);
        if (info != null) callbacks.onInfo(info, infoToken);
        if (deliverBest) callbacks.onBestMove(best, bestToken);
    }

    @Override
    public void onClosed() {
        boolean unexpected;
        synchronized (this) {
            unexpected = !quitting;
            if (unexpected) {
                ready = false;
                proc = null;
                inflight.clear();
                activeToken = -1;
                pending = null;
            }
        }
        if (unexpected) callbacks.onFailure("The engine process ended unexpectedly.");
    }

    /** Parses an "info" line; returns null when it carries no score and principal variation. */
    static SearchInfo parseInfo(String line) {
        String[] t = line.split("\\s+");
        SearchInfo si = new SearchInfo();
        boolean hasScore = false;
        int multipv = 1;
        try {
            for (int i = 1; i < t.length; i++) {
                String k = t[i];
                if (k.equals("string")) return null;
                if (k.equals("depth")) si.depth = Integer.parseInt(t[++i]);
                else if (k.equals("seldepth")) si.selDepth = Integer.parseInt(t[++i]);
                else if (k.equals("multipv")) multipv = Integer.parseInt(t[++i]);
                else if (k.equals("nodes")) si.nodes = Long.parseLong(t[++i]);
                else if (k.equals("nps")) si.nps = Long.parseLong(t[++i]);
                else if (k.equals("time")) si.timeMs = Long.parseLong(t[++i]);
                else if (k.equals("score")) {
                    String kind = t[++i];
                    si.mate = kind.equals("mate");
                    si.score = Integer.parseInt(t[++i]);
                    hasScore = true;
                } else if (k.equals("lowerbound")) si.lowerBound = true;
                else if (k.equals("upperbound")) si.upperBound = true;
                else if (k.equals("wdl")) {
                    si.wdlWin = Integer.parseInt(t[++i]);
                    si.wdlDraw = Integer.parseInt(t[++i]);
                    si.wdlLoss = Integer.parseInt(t[++i]);
                } else if (k.equals("pv")) {
                    for (int j = i + 1; j < t.length; j++) si.pv.add(t[j]);
                    break;
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
        if (!hasScore || si.pv.isEmpty() || multipv != 1) return null;
        return si;
    }
}
