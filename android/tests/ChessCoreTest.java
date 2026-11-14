import dev.stuckfih.chess.core.Board;
import dev.stuckfih.chess.core.Game;
import dev.stuckfih.chess.core.Move;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dependency-free tests for the chess rules core (run with plain `java`, no JUnit needed):
 *   javac -d /tmp/t app/src/main/java/dev/stuckfih/chess/core/*.java tests/ChessCoreTest.java
 *   java -cp /tmp/t ChessCoreTest [full]
 */
public class ChessCoreTest {
    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: " + what + "\n   expected: " + expected + "\n   actual:   " + actual);
        }
    }

    static long perft(Board b, int depth) {
        List<Move> ms = b.legalMoves();
        if (depth == 1) return ms.size();
        long n = 0;
        for (Move m : ms) n += perft(b.play(m), depth - 1);
        return n;
    }

    static void perftSuite(boolean full) {
        Object[][] suite = {
            // name, fen, counts for depth 1..N (quick), extra depth for full
            {"startpos", Board.START_FEN, new long[]{20, 400, 8902, 197281}, new long[]{4865609}},
            {"kiwipete", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                new long[]{48, 2039, 97862}, new long[]{4085603}},
            {"position3", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                new long[]{14, 191, 2812, 43238}, new long[]{674624}},
            {"position4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                new long[]{6, 264, 9467}, new long[]{422333}},
            {"position4-mirrored", "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
                new long[]{6, 264, 9467}, new long[]{422333}},
            {"position5", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                new long[]{44, 1486, 62379}, new long[]{2103487}},
            {"position6", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
                new long[]{46, 2079, 89890}, new long[]{3894594}},
        };
        for (Object[] row : suite) {
            String name = (String) row[0];
            Board b = Board.fromFen((String) row[1]);
            long[] quick = (long[]) row[2];
            long[] deep = (long[]) row[3];
            long t0 = System.currentTimeMillis();
            for (int d = 1; d <= quick.length; d++) eq(quick[d - 1], perft(b, d), "perft " + name + " depth " + d);
            if (full) eq(deep[0], perft(b, quick.length + 1), "perft " + name + " depth " + (quick.length + 1));
            System.out.println("  perft " + name + " ok (" + (System.currentTimeMillis() - t0) + " ms)");
        }
    }

    static void fenRoundTrips() {
        String[] fens = {
            Board.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
            "4k3/8/8/8/8/8/8/4K2R w K - 12 40",
        };
        for (String f : fens) eq(f, Board.fromFen(f).toFen(), "FEN round trip " + f);
        // an en passant square nobody can capture on is dropped
        eq("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            Board.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1").toFen(), "unusable ep dropped");
        String[] bad = {
            "8/8/8/8/8/8/8/8 w - - 0 1",                                  // no kings
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w KQkq - 0 1",    // short rank
            "4k3/8/8/8/8/8/8/4K2R b - - 0 1",                              // white gave check illegally? black to move, black king not attacked -> fine
            "4k3/8/8/8/8/8/8/R3K3 w - - 0 1x",                             // bad counter
            "4k3/4R3/8/8/8/8/8/4K3 w - - 0 1",                             // side not to move (black) is in check
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1",   // bad side
        };
        boolean[] shouldFail = {true, true, false, true, true, true};
        for (int i = 0; i < bad.length; i++) {
            boolean threw = false;
            try {
                Board.fromFen(bad[i]);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            eq(shouldFail[i], threw, "FEN validation #" + i + ": " + bad[i]);
        }
    }

    static Game playSan(String start, String... uci) {
        Game g = new Game(start);
        for (String s : uci) {
            Move m = g.current().parseUci(s);
            check(m != null && g.play(m), "play " + s);
        }
        return g;
    }

    static void sanTests() {
        Game g = playSan(Board.START_FEN, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7");
        eq("[e4, e5, Nf3, Nc6, Bb5, a6, Ba4, Nf6, O-O, Be7]", g.sanMoves().toString(), "Ruy Lopez SAN");
        eq("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7", g.movetext(), "movetext");

        Game mate = playSan(Board.START_FEN, "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7");
        eq("Qxf7#", mate.sanMoves().get(6), "scholar's mate SAN");
        eq(Game.Status.CHECKMATE, mate.status(), "scholar's mate status");
        eq("1-0", mate.resultTag(), "scholar's mate result");
        check(!mate.play(new Move(Move.squareIndex("e8"), Move.squareIndex("f7"))), "no moves after mate");

        Board knights = Board.fromFen("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1");
        eq("Nbd2", knights.san(knights.parseUci("b1d2")), "file disambiguation b");
        eq("Nfd2", knights.san(knights.parseUci("f1d2")), "file disambiguation f");
        Board rooks = Board.fromFen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1");
        eq("R5a3", rooks.san(rooks.parseUci("a5a3")), "rank disambiguation 5");
        eq("R1a3", rooks.san(rooks.parseUci("a1a3")), "rank disambiguation 1");
        Board promo = Board.fromFen("7k/P7/8/8/8/8/8/K7 w - - 0 1");
        eq("a8=Q+", promo.san(promo.parseUci("a7a8q")), "promotion with check");
        eq("a8=N", promo.san(promo.parseUci("a7a8n")), "underpromotion");
        check(promo.parseUci("a7a8k") == null && promo.parseUci("a7a8") == null, "bad promotion rejected");
        Board ep = Board.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3");
        eq("exf6", ep.san(ep.parseUci("e5f6")), "en passant SAN");
        eq("rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3", ep.play(ep.parseUci("e5f6")).toFen(), "en passant result");
        Board castle = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        eq("O-O-O", castle.san(castle.parseUci("e1c1")), "queenside castling SAN");
        Board afterWhite = castle.play(castle.parseUci("e1g1"));
        eq("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", afterWhite.toFen(), "white kingside castling result");
        eq("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", afterWhite.play(afterWhite.parseUci("e8c8")).toFen(), "black queenside castling result");
    }

    static void statusTests() {
        eq(Game.Status.STALEMATE, new Game("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").status(), "stalemate");
        eq(Game.Status.INSUFFICIENT_MATERIAL, new Game("8/8/8/8/8/8/3k4/K7 w - - 0 1").status(), "K vs K");
        eq(Game.Status.INSUFFICIENT_MATERIAL, new Game("5b1k/8/8/8/8/8/8/K1B5 w - - 0 1").status(), "same-colour bishops");
        eq(Game.Status.ONGOING, new Game("4b2k/8/8/8/8/8/8/K1B5 w - - 0 1").status(), "opposite-colour bishops");
        eq(Game.Status.ONGOING, new Game("7k/8/8/8/8/8/8/KNN5 w - - 0 1").status(), "two knights are not auto-drawn");
        eq(Game.Status.FIFTY_MOVES, new Game("4k3/8/8/8/8/8/8/4K2R w K - 100 80").status(), "fifty-move rule");
        Game rep = playSan(Board.START_FEN, "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1");
        eq(Game.Status.ONGOING, rep.status(), "no repetition yet");
        check(rep.play(new Move(Move.squareIndex("f6"), Move.squareIndex("g8"))), "last repetition move");
        eq(Game.Status.REPETITION, rep.status(), "threefold repetition");
        // undo
        Game g = playSan(Board.START_FEN, "e2e4", "e7e5", "g1f3");
        g.undo();
        eq(2, g.plyCount(), "undo ply count");
        eq("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", g.current().toFen(), "undo position");
        while (g.undo() != null) { }
        eq(Board.START_FEN, g.current().toFen(), "undo to start");
        eq("", g.movetext(), "empty movetext");
        // PGN
        Game p = playSan(Board.START_FEN, "e2e4", "e7e5", "g1f3");
        String pgn = p.toPgn("Me", "Stockfish");
        check(pgn.contains("[White \"Me\"]") && pgn.contains("[Black \"Stockfish\"]") && pgn.contains("[Result \"*\"]")
            && pgn.contains("1. e4 e5 2. Nf3 *"), "PGN content:\n" + pgn);
        // restore from moves, including a black-to-move start
        Game r = Game.fromMoves(Board.START_FEN, Arrays.asList("e2e4", "e7e5", "bogus", "g1f3"));
        eq(2, r.plyCount(), "fromMoves stops at the first bad move");
        Game bs = playSan("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", "e7e5");
        eq("1... e5", bs.movetext(), "movetext from a black-to-move start");
    }

    static void randomGames() {
        // Random playouts must never crash or produce inconsistent states.
        java.util.Random rnd = new java.util.Random(12345);
        int plies = 0, games = 0;
        for (int gi = 0; gi < 60; gi++) {
            Game g = new Game();
            games++;
            while (!g.isOver() && g.plyCount() < 400) {
                List<Move> legal = g.current().legalMoves();
                Move m = legal.get(rnd.nextInt(legal.size()));
                check(g.play(m), "random game move " + m);
                // FEN of every position must re-parse to the same FEN
                String fen = g.current().toFen();
                if (!fen.equals(Board.fromFen(fen).toFen())) check(false, "FEN stability " + fen);
                plies++;
            }
        }
        System.out.println("  random playouts: " + games + " games, " + plies + " plies");
    }

    public static void main(String[] args) {
        boolean full = args.length > 0 && args[0].equals("full");
        long t0 = System.currentTimeMillis();
        System.out.println("Perft" + (full ? " (full depth)" : ""));
        perftSuite(full);
        fenRoundTrips();
        sanTests();
        statusTests();
        randomGames();
        System.out.println(checks + " checks, " + failures + " failures, " + (System.currentTimeMillis() - t0) + " ms");
        if (failures > 0) System.exit(1);
    }
}
