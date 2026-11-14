import dev.stuckfih.chess.EngineSession;
import dev.stuckfih.chess.UciProcess;
import dev.stuckfih.chess.core.Board;
import dev.stuckfih.chess.core.Game;
import dev.stuckfih.chess.core.Move;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Talks to a real UCI engine to check two things:
 *   1. our move generator agrees with the engine's own ("go perft 1") on thousands of positions;
 *   2. EngineSession handles the handshake, searches, cancellation and shutdown correctly.
 *
 * Usage: java -cp ... EngineIntegrationTest [fast] <engine command...>
 *   e.g.  EngineIntegrationTest /usr/games/stockfish
 *         EngineIntegrationTest fast qemu-aarch64 ./libstockfish.so      (slow emulated engine)
 */
public class EngineIntegrationTest {
    static int checks = 0, failures = 0;
    static List<String> command;
    static boolean fast;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        } else if (verbose && !what.isEmpty()) {
            System.out.println("  ok: " + what);
        }
    }

    static boolean verbose = System.getenv("TEST_VERBOSE") != null;

    // ------------------------------------------------------------------ 1. rules vs engine perft 1

    static Set<String> enginePerft1(UciProcess p, BlockingQueue<String> q, String fen) throws Exception {
        q.clear();
        p.send("position fen " + fen);
        p.send("go perft 1");
        Set<String> moves = new HashSet<>();
        while (true) {
            String line = q.poll(20, TimeUnit.SECONDS);
            if (line == null) throw new IllegalStateException("engine did not answer perft for " + fen);
            if (line.startsWith("Nodes searched")) return moves;
            int c = line.indexOf(':');
            if (c > 0 && line.indexOf(' ') < 0 || (c > 0 && line.substring(c).trim().matches(":\\s*\\d+"))) {
                String mv = line.substring(0, c).trim();
                if (mv.length() >= 4 && mv.length() <= 5 && mv.matches("[a-h][1-8][a-h][1-8][nbrq]?")) moves.add(mv);
            }
        }
    }

    static void rulesCrossCheck(int games) throws Exception {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        UciProcess p = UciProcess.start(command, new UciProcess.LineListener() {
            public void onLine(String line) {
                q.add(line);
            }

            public void onClosed() {
            }
        });
        p.send("uci");
        while (true) {
            String l = q.poll(60, TimeUnit.SECONDS);
            if (l == null) throw new IllegalStateException("no uciok");
            if (l.equals("uciok")) break;
        }
        Random rnd = new Random(2026);
        int positions = 0, promotions = 0, castles = 0, eps = 0;
        for (int g = 0; g < games; g++) {
            Game game = new Game();
            boolean greedy = g % 2 == 1; // every other game favours captures/promotions/castling
            while (!game.isOver() && game.plyCount() < 300) {
                Board b = game.current();
                List<Move> legal = b.legalMoves();
                String fen = b.toFen();
                Set<String> mine = new HashSet<>();
                for (Move m : legal) {
                    mine.add(m.uci());
                    if (m.promo != 0) promotions++;
                    if (Math.abs(b.pieceAt(m.from)) == Board.KING && Math.abs(m.to - m.from) == 2) castles++;
                    if (Math.abs(b.pieceAt(m.from)) == Board.PAWN && m.to == b.epSquare()) eps++;
                }
                // sample positions: all of the first 30 plies of each game, then every 3rd
                if (game.plyCount() < 30 || game.plyCount() % 3 == 0) {
                    Set<String> theirs = enginePerft1(p, q, fen);
                    positions++;
                    if (!theirs.equals(mine)) {
                        check(false, "move lists differ in " + fen + "\n   mine:   " + new java.util.TreeSet<>(mine)
                            + "\n   theirs: " + new java.util.TreeSet<>(theirs));
                    } else {
                        check(true, "");
                    }
                }
                Move pick = null;
                if (greedy) {
                    List<Move> good = new ArrayList<>();
                    for (Move m : legal) {
                        boolean capture = b.pieceAt(m.to) != 0;
                        boolean castle = Math.abs(b.pieceAt(m.from)) == Board.KING && Math.abs(m.to - m.from) == 2;
                        if (m.promo != 0 || capture || castle || Math.abs(b.pieceAt(m.from)) == Board.PAWN) good.add(m);
                    }
                    if (!good.isEmpty() && rnd.nextInt(10) < 8) pick = good.get(rnd.nextInt(good.size()));
                }
                if (pick == null) pick = legal.get(rnd.nextInt(legal.size()));
                game.play(pick);
            }
        }
        p.quit();
        System.out.println("  rules vs engine: " + positions + " positions compared (" + promotions
            + " promotion, " + castles + " castling, " + eps + " en-passant moves seen)");
    }

    // ------------------------------------------------------------------ 2. EngineSession

    static class Collector implements EngineSession.Callbacks {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        volatile int infos = 0;
        volatile int lastDepth = 0;

        public void onReady(String name) {
            events.add("ready:" + name);
        }

        public void onInfo(EngineSession.SearchInfo info, int token) {
            infos++;
            lastDepth = info.depth;
        }

        public void onBestMove(String move, int token) {
            events.add("best:" + token + ":" + move);
        }

        public void onFailure(String message) {
            events.add("fail:" + message);
        }
    }

    static Map<String, String> opts() {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("Threads", "1");
        o.put("Hash", "16");
        o.put("UCI_ShowWDL", "true");
        o.put("Move Overhead", "10");
        return o;
    }

    static String next(Collector c, long ms) throws InterruptedException {
        return c.events.poll(ms, TimeUnit.MILLISECONDS);
    }

    static void sessionTests() throws Exception {
        final long startup = 90_000;
        final int shortMs = fast ? 1500 : 150;

        Collector c = new Collector();
        EngineSession s = new EngineSession(c);
        s.start(command, opts());
        // a search issued before the engine is ready must be queued, not lost
        List<String> none = new ArrayList<>();
        int t0 = s.search(Board.START_FEN, none, shortMs);
        String ev = next(c, startup);
        check(ev != null && ev.startsWith("ready:"), "engine becomes ready (" + ev + ")");
        System.out.println("  engine: " + (ev == null ? "?" : ev.substring(6)));
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + t0 + ":"), "queued search answered: " + ev);
        if (ev != null) {
            String mv = ev.substring(ev.lastIndexOf(':') + 1);
            check(Board.startPosition().parseUci(mv) != null, "queued search returned a legal move: " + mv);
        }

        // plain search with info lines
        c.infos = 0;
        int t1 = s.search(Board.START_FEN, Arrays.asList("e2e4", "e7e5"), shortMs);
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + t1 + ":"), "search 1 answered: " + ev);
        check(c.infos > 0, "info lines delivered (" + c.infos + ")");

        // superseded search: only the newest answer may be delivered, exactly once
        int tA = s.search(Board.START_FEN, none, 5000);
        Thread.sleep(fast ? 800 : 150);
        int tB = s.search(Board.START_FEN, Arrays.asList("d2d4"), shortMs);
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + tB + ":"), "only the newest search is delivered: " + ev + " (A=" + tA + ", B=" + tB + ")");
        check(next(c, fast ? 2500 : 800) == null, "no stale best move after a superseded search");

        // cancel: no answer at all, and the next search still works
        s.search(Board.START_FEN, none, 5000);
        Thread.sleep(fast ? 800 : 150);
        s.cancel();
        check(next(c, fast ? 2500 : 800) == null, "cancelled search delivers nothing");
        int tD = s.search(Board.START_FEN, Arrays.asList("c2c4"), shortMs);
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + tD + ":"), "search after cancel works: " + ev);

        // new game + option change + strength limit
        s.newGame();
        s.setOption("UCI_LimitStrength", "true");
        s.setOption("UCI_Elo", "1500");
        int tE = s.search(Board.START_FEN, none, shortMs);
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + tE + ":"), "limited-strength search works: " + ev);
        s.setOption("UCI_LimitStrength", "false");

        // a short game against ourselves: every engine move must be legal for our rules
        Game game = new Game();
        int plies = fast ? 8 : 40;
        boolean allLegal = true;
        for (int i = 0; i < plies && !game.isOver(); i++) {
            int t = s.search(game.startFen(), game.uciMoves(), fast ? 1200 : 40);
            ev = next(c, startup);
            if (ev == null || !ev.startsWith("best:" + t + ":")) {
                check(false, "self-play ply " + i + " answered: " + ev);
                allLegal = false;
                break;
            }
            Move m = game.current().parseUci(ev.substring(ev.lastIndexOf(':') + 1));
            if (m == null || !game.play(m)) {
                check(false, "self-play move illegal at ply " + i + ": " + ev + " in " + game.current().toFen());
                allLegal = false;
                break;
            }
        }
        check(allLegal, "self-play produced only legal moves (" + game.plyCount() + " plies)");

        // orderly shutdown: no failure callback and the process is gone
        s.shutdown();
        check(next(c, fast ? 3000 : 1200) == null, "no failure reported after shutdown");
        check(!s.isRunning(), "session not running after shutdown");

        // restart on the same session object
        s.start(command, opts());
        ev = next(c, startup);
        check(ev != null && ev.startsWith("ready:"), "session can be restarted: " + ev);
        int tF = s.search(Board.START_FEN, none, shortMs);
        ev = next(c, startup);
        check(ev != null && ev.startsWith("best:" + tF + ":"), "search after restart works: " + ev);

        // unexpected death is reported
        // match the process *name* exactly so this JVM (whose command line mentions the engine) survives
        String exe = command.get(0);
        exe = exe.substring(exe.lastIndexOf('/') + 1);
        Process killer = new ProcessBuilder("pkill", "-9", "-x", exe).start();
        killer.waitFor();
        ev = next(c, 10_000);
        check(ev != null && ev.startsWith("fail:"), "unexpected engine death reported: " + ev);
        check(!s.isRunning(), "session knows the engine died");
        s.shutdown();
    }

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        if (!a.isEmpty() && a.get(0).equals("fast")) {
            fast = true;
            a.remove(0);
        }
        if (a.isEmpty()) a.add("/usr/games/stockfish");
        command = a;
        System.out.println("Engine command: " + command + (fast ? "  [fast mode: emulated/slow engine]" : ""));
        long t0 = System.currentTimeMillis();
        System.out.println("Rules cross-check");
        rulesCrossCheck(fast ? 3 : 24);
        System.out.println("EngineSession");
        sessionTests();
        System.out.println(checks + " checks, " + failures + " failures, " + (System.currentTimeMillis() - t0) / 1000 + " s");
        System.exit(failures > 0 ? 1 : 0);
    }
}
