package dev.stuckfih.chess.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A game in progress: the move history, end-of-game detection, undo and PGN export. */
public final class Game {
    public enum Status {
        ONGOING, CHECKMATE, STALEMATE, FIFTY_MOVES, REPETITION, INSUFFICIENT_MATERIAL
    }

    private final String startFen;
    private final List<Board> boards = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<String> sans = new ArrayList<>();
    private Status status = Status.ONGOING;

    public Game() {
        this(Board.START_FEN);
    }

    /** @throws IllegalArgumentException when the FEN is invalid */
    public Game(String fen) {
        Board b = Board.fromFen(fen);
        startFen = b.toFen();
        boards.add(b);
        refresh();
    }

    /** Rebuilds a game from a start position and UCI moves; stops silently at the first illegal move. */
    public static Game fromMoves(String startFen, List<String> uciMoves) {
        Game g = new Game(startFen);
        for (String s : uciMoves) {
            Move m = g.current().parseUci(s);
            if (m == null || !g.play(m)) break;
        }
        return g;
    }

    public String startFen() {
        return startFen;
    }

    public Board current() {
        return boards.get(boards.size() - 1);
    }

    public Board boardAt(int ply) {
        return boards.get(ply);
    }

    public int plyCount() {
        return moves.size();
    }

    public Move lastMove() {
        return moves.isEmpty() ? null : moves.get(moves.size() - 1);
    }

    public List<Move> moves() {
        return new ArrayList<>(moves);
    }

    public List<String> sanMoves() {
        return new ArrayList<>(sans);
    }

    public List<String> uciMoves() {
        List<String> out = new ArrayList<>(moves.size());
        for (Move m : moves) out.add(m.uci());
        return out;
    }

    public Status status() {
        return status;
    }

    public boolean isOver() {
        return status != Status.ONGOING;
    }

    /** Plays a move if it is legal and the game is still running. */
    public boolean play(Move m) {
        if (status != Status.ONGOING) return false;
        Board cur = current();
        Move legal = cur.findMove(m);
        if (legal == null) return false;
        sans.add(cur.san(legal));
        moves.add(legal);
        boards.add(cur.play(legal));
        refresh();
        return true;
    }

    /** Takes back the last move; returns it, or null when there is nothing to undo. */
    public Move undo() {
        if (moves.isEmpty()) return null;
        Move m = moves.remove(moves.size() - 1);
        sans.remove(sans.size() - 1);
        boards.remove(boards.size() - 1);
        refresh();
        return m;
    }

    private void refresh() {
        Board b = current();
        if (b.legalMoves().isEmpty()) {
            status = b.inCheck() ? Status.CHECKMATE : Status.STALEMATE;
        } else if (b.halfmoveClock() >= 100) {
            status = Status.FIFTY_MOVES;
        } else if (b.insufficientMaterial()) {
            status = Status.INSUFFICIENT_MATERIAL;
        } else if (repetitionCount() >= 3) {
            status = Status.REPETITION;
        } else {
            status = Status.ONGOING;
        }
    }

    private int repetitionCount() {
        Map<String, Integer> seen = new HashMap<>();
        int latest = 0;
        String key = null;
        for (Board b : boards) {
            key = b.positionKey();
            Integer n = seen.get(key);
            latest = n == null ? 1 : n + 1;
            seen.put(key, latest);
        }
        return latest;
    }

    /** "1-0", "0-1", "1/2-1/2" or "*". */
    public String resultTag() {
        switch (status) {
            case CHECKMATE:
                return current().whiteToMove() ? "0-1" : "1-0";
            case ONGOING:
                return "*";
            default:
                return "1/2-1/2";
        }
    }

    public String statusText() {
        switch (status) {
            case CHECKMATE:
                return "Checkmate \u2014 " + (current().whiteToMove() ? "Black" : "White") + " wins";
            case STALEMATE:
                return "Stalemate \u2014 draw";
            case FIFTY_MOVES:
                return "Draw by the fifty-move rule";
            case REPETITION:
                return "Draw by threefold repetition";
            case INSUFFICIENT_MATERIAL:
                return "Draw \u2014 insufficient material";
            default:
                return (current().whiteToMove() ? "White" : "Black") + " to move";
        }
    }

    /** Move list such as "1. e4 e5 2. Nf3 Nc6" (empty string when no moves were played). */
    public String movetext() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < moves.size(); i++) {
            Board before = boards.get(i);
            if (before.whiteToMove()) {
                sb.append(before.fullmoveNumber()).append(". ");
            } else if (i == 0) {
                sb.append(before.fullmoveNumber()).append("... ");
            }
            sb.append(sans.get(i)).append(' ');
        }
        return sb.toString().trim();
    }

    public String toPgn(String whiteName, String blackName) {
        StringBuilder sb = new StringBuilder();
        String date = new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date());
        sb.append("[Event \"Casual game\"]\n");
        sb.append("[Site \"Stockfish for Android\"]\n");
        sb.append("[Date \"").append(date).append("\"]\n");
        sb.append("[Round \"-\"]\n");
        sb.append("[White \"").append(whiteName).append("\"]\n");
        sb.append("[Black \"").append(blackName).append("\"]\n");
        sb.append("[Result \"").append(resultTag()).append("\"]\n");
        if (!startFen.equals(Board.fromFen(Board.START_FEN).toFen())) {
            sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n");
        }
        sb.append('\n');
        String text = movetext();
        String tokens = text.isEmpty() ? resultTag() : text + " " + resultTag();
        // wrap at 80 columns on token boundaries
        StringBuilder line = new StringBuilder();
        for (String tok : tokens.split(" ")) {
            if (line.length() + tok.length() + 1 > 80) {
                sb.append(line.toString().trim()).append('\n');
                line.setLength(0);
            }
            line.append(tok).append(' ');
        }
        sb.append(line.toString().trim()).append('\n');
        return sb.toString();
    }
}
