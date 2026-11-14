package dev.stuckfih.chess.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-by-convention chess position with full legal move generation.
 * Plain Java with no Android dependencies so it can be unit-tested on any JVM.
 *
 * Piece encoding in the board array: +type for White, -type for Black
 * (PAWN = 1 ... KING = 6). Squares are 0..63 with a1 = 0 and h8 = 63.
 */
public final class Board {
    public static final int EMPTY = 0, PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    public static final int CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final int[] KN_DF = {1, 2, 2, 1, -1, -2, -2, -1};
    private static final int[] KN_DR = {2, 1, -1, -2, -2, -1, 1, 2};
    private static final int[] K_DF = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] K_DR = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] ROOK_DF = {1, -1, 0, 0};
    private static final int[] ROOK_DR = {0, 0, 1, -1};
    private static final int[] BISHOP_DF = {1, 1, -1, -1};
    private static final int[] BISHOP_DR = {1, -1, 1, -1};

    /** Castling rights that survive a move touching the given square. */
    private static final int[] CASTLE_KEEP = new int[64];

    static {
        for (int i = 0; i < 64; i++) CASTLE_KEEP[i] = 15;
        CASTLE_KEEP[0] = ~CASTLE_WQ & 15;
        CASTLE_KEEP[7] = ~CASTLE_WK & 15;
        CASTLE_KEEP[56] = ~CASTLE_BQ & 15;
        CASTLE_KEEP[63] = ~CASTLE_BK & 15;
    }

    private static final String PIECE_KEY_CHARS = "kqrbnp.PNBRQK";

    private final byte[] sq = new byte[64];
    private boolean white = true;
    private int castle;
    private int ep = -1;
    private int half;
    private int full = 1;
    private int wk = -1;
    private int bk = -1;

    private Board() {
    }

    private Board copy() {
        Board b = new Board();
        System.arraycopy(sq, 0, b.sq, 0, 64);
        b.white = white;
        b.castle = castle;
        b.ep = ep;
        b.half = half;
        b.full = full;
        b.wk = wk;
        b.bk = bk;
        return b;
    }

    public static Board startPosition() {
        return fromFen(START_FEN);
    }

    // ------------------------------------------------------------------ accessors

    public boolean whiteToMove() {
        return white;
    }

    /** Signed piece on the square: +type for White, -type for Black, 0 when empty. */
    public int pieceAt(int square) {
        return sq[square];
    }

    public int castlingRights() {
        return castle;
    }

    public int epSquare() {
        return ep;
    }

    public int halfmoveClock() {
        return half;
    }

    public int fullmoveNumber() {
        return full;
    }

    public int kingSquare(boolean whiteKing) {
        return whiteKing ? wk : bk;
    }

    // ------------------------------------------------------------------ FEN

    public static Board fromFen(String fen) {
        if (fen == null) throw new IllegalArgumentException("FEN is empty");
        String[] t = fen.trim().split("\\s+");
        if (t.length < 2) throw new IllegalArgumentException("FEN needs piece placement and side to move");
        Board b = new Board();
        String[] ranks = t[0].split("/", -1);
        if (ranks.length != 8) throw new IllegalArgumentException("FEN needs 8 ranks");
        int whiteKings = 0, blackKings = 0;
        for (int i = 0; i < 8; i++) {
            int r = 7 - i;
            int f = 0;
            for (int k = 0; k < ranks[i].length(); k++) {
                char c = ranks[i].charAt(k);
                if (c >= '1' && c <= '8') {
                    f += c - '0';
                } else {
                    int type = "pnbrqk".indexOf(Character.toLowerCase(c)) + 1;
                    if (type == 0 || f > 7) throw new IllegalArgumentException("Bad FEN piece placement");
                    if (type == PAWN && (r == 0 || r == 7)) throw new IllegalArgumentException("Pawn on back rank");
                    int s = r * 8 + f;
                    boolean isWhite = Character.isUpperCase(c);
                    b.sq[s] = (byte) (isWhite ? type : -type);
                    if (type == KING) {
                        if (isWhite) {
                            b.wk = s;
                            whiteKings++;
                        } else {
                            b.bk = s;
                            blackKings++;
                        }
                    }
                    f++;
                }
            }
            if (f != 8) throw new IllegalArgumentException("FEN rank does not have 8 files");
        }
        if (whiteKings != 1 || blackKings != 1) throw new IllegalArgumentException("Each side needs exactly one king");

        if (t[1].equals("w")) b.white = true;
        else if (t[1].equals("b")) b.white = false;
        else throw new IllegalArgumentException("Bad side to move");

        int rights = 0;
        if (t.length > 2 && !t[2].equals("-")) {
            for (char c : t[2].toCharArray()) {
                if (c == 'K') rights |= CASTLE_WK;
                else if (c == 'Q') rights |= CASTLE_WQ;
                else if (c == 'k') rights |= CASTLE_BK;
                else if (c == 'q') rights |= CASTLE_BQ;
                else throw new IllegalArgumentException("Bad castling field");
            }
        }
        // Drop rights that do not match the pieces on the board.
        if (b.sq[4] != KING) rights &= ~(CASTLE_WK | CASTLE_WQ);
        if (b.sq[7] != ROOK) rights &= ~CASTLE_WK;
        if (b.sq[0] != ROOK) rights &= ~CASTLE_WQ;
        if (b.sq[60] != -KING) rights &= ~(CASTLE_BK | CASTLE_BQ);
        if (b.sq[63] != -ROOK) rights &= ~CASTLE_BK;
        if (b.sq[56] != -ROOK) rights &= ~CASTLE_BQ;
        b.castle = rights;

        b.ep = -1;
        if (t.length > 3 && !t[3].equals("-")) {
            int e = Move.squareIndex(t[3]);
            if (e < 0) throw new IllegalArgumentException("Bad en passant square");
            int expectedRank = b.white ? 5 : 2;
            int pawnSq = b.white ? e - 8 : e + 8;
            if ((e >> 3) == expectedRank && b.sq[e] == EMPTY && b.sq[pawnSq] == (b.white ? -PAWN : PAWN)) {
                b.ep = e;
            }
        }
        try {
            if (t.length > 4) b.half = Math.max(0, Integer.parseInt(t[4]));
            if (t.length > 5) b.full = Math.max(1, Integer.parseInt(t[5]));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Bad move counters");
        }
        // The side that just moved must not have left its king in check.
        if (b.attacked(b.white ? b.bk : b.wk, b.white)) {
            throw new IllegalArgumentException("Illegal position: the side not to move is in check");
        }
        return b;
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder(90);
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = sq[r * 8 + f];
                if (p == 0) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                char c = "pnbrqk".charAt(Math.abs(p) - 1);
                sb.append(p > 0 ? Character.toUpperCase(c) : c);
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(white ? " w " : " b ");
        if (castle == 0) {
            sb.append('-');
        } else {
            if ((castle & CASTLE_WK) != 0) sb.append('K');
            if ((castle & CASTLE_WQ) != 0) sb.append('Q');
            if ((castle & CASTLE_BK) != 0) sb.append('k');
            if ((castle & CASTLE_BQ) != 0) sb.append('q');
        }
        sb.append(' ');
        sb.append(ep >= 0 && epCapturePossible() ? Move.squareName(ep) : "-");
        sb.append(' ').append(half).append(' ').append(full);
        return sb.toString();
    }

    // ------------------------------------------------------------------ attacks and checks

    /** Is square s attacked by any piece of the given colour? */
    private boolean attacked(int s, boolean byWhite) {
        int f = s & 7, r = s >> 3;
        int side = byWhite ? 1 : -1;
        int pr = byWhite ? r - 1 : r + 1;
        if (pr >= 0 && pr < 8) {
            if (f > 0 && sq[pr * 8 + f - 1] == side * PAWN) return true;
            if (f < 7 && sq[pr * 8 + f + 1] == side * PAWN) return true;
        }
        for (int i = 0; i < 8; i++) {
            int nf = f + KN_DF[i], nr = r + KN_DR[i];
            if (nf >= 0 && nf < 8 && nr >= 0 && nr < 8 && sq[nr * 8 + nf] == side * KNIGHT) return true;
            nf = f + K_DF[i];
            nr = r + K_DR[i];
            if (nf >= 0 && nf < 8 && nr >= 0 && nr < 8 && sq[nr * 8 + nf] == side * KING) return true;
        }
        for (int d = 0; d < 4; d++) {
            int nf = f + ROOK_DF[d], nr = r + ROOK_DR[d];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int v = sq[nr * 8 + nf];
                if (v != 0) {
                    if (v == side * ROOK || v == side * QUEEN) return true;
                    break;
                }
                nf += ROOK_DF[d];
                nr += ROOK_DR[d];
            }
            nf = f + BISHOP_DF[d];
            nr = r + BISHOP_DR[d];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int v = sq[nr * 8 + nf];
                if (v != 0) {
                    if (v == side * BISHOP || v == side * QUEEN) return true;
                    break;
                }
                nf += BISHOP_DF[d];
                nr += BISHOP_DR[d];
            }
        }
        return false;
    }

    public boolean inCheck() {
        return attacked(white ? wk : bk, !white);
    }

    // ------------------------------------------------------------------ move generation

    private void genPseudo(List<Move> out) {
        final int side = white ? 1 : -1;
        for (int s = 0; s < 64; s++) {
            int p = sq[s] * side;
            if (p <= 0) continue;
            int f = s & 7, r = s >> 3;
            switch (p) {
                case PAWN:
                    genPawn(s, f, r, out);
                    break;
                case KNIGHT:
                    for (int i = 0; i < 8; i++) {
                        int nf = f + KN_DF[i], nr = r + KN_DR[i];
                        if (nf < 0 || nf > 7 || nr < 0 || nr > 7) continue;
                        int t = nr * 8 + nf;
                        if (sq[t] * side <= 0) out.add(new Move(s, t));
                    }
                    break;
                case BISHOP:
                    slide(s, BISHOP_DF, BISHOP_DR, side, out);
                    break;
                case ROOK:
                    slide(s, ROOK_DF, ROOK_DR, side, out);
                    break;
                case QUEEN:
                    slide(s, BISHOP_DF, BISHOP_DR, side, out);
                    slide(s, ROOK_DF, ROOK_DR, side, out);
                    break;
                case KING:
                    for (int i = 0; i < 8; i++) {
                        int nf = f + K_DF[i], nr = r + K_DR[i];
                        if (nf < 0 || nf > 7 || nr < 0 || nr > 7) continue;
                        int t = nr * 8 + nf;
                        if (sq[t] * side <= 0) out.add(new Move(s, t));
                    }
                    genCastling(s, out);
                    break;
                default:
                    break;
            }
        }
    }

    private void slide(int s, int[] dfs, int[] drs, int side, List<Move> out) {
        int f = s & 7, r = s >> 3;
        for (int d = 0; d < dfs.length; d++) {
            int nf = f + dfs[d], nr = r + drs[d];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int t = nr * 8 + nf;
                int v = sq[t] * side;
                if (v > 0) break;
                out.add(new Move(s, t));
                if (v < 0) break;
                nf += dfs[d];
                nr += drs[d];
            }
        }
    }

    private void genPawn(int s, int f, int r, List<Move> out) {
        int dir = white ? 1 : -1;
        int nr = r + dir;
        if (nr < 0 || nr > 7) return;
        boolean promo = nr == (white ? 7 : 0);
        int t = nr * 8 + f;
        if (sq[t] == EMPTY) {
            addPawnMove(s, t, promo, out);
            if (r == (white ? 1 : 6)) {
                int t2 = (r + 2 * dir) * 8 + f;
                if (sq[t2] == EMPTY) out.add(new Move(s, t2));
            }
        }
        int side = white ? 1 : -1;
        for (int df = -1; df <= 1; df += 2) {
            int nf = f + df;
            if (nf < 0 || nf > 7) continue;
            int c = nr * 8 + nf;
            if (sq[c] * side < 0) addPawnMove(s, c, promo, out);
            else if (c == ep) out.add(new Move(s, c));
        }
    }

    private static void addPawnMove(int from, int to, boolean promo, List<Move> out) {
        if (promo) {
            out.add(new Move(from, to, QUEEN));
            out.add(new Move(from, to, ROOK));
            out.add(new Move(from, to, BISHOP));
            out.add(new Move(from, to, KNIGHT));
        } else {
            out.add(new Move(from, to));
        }
    }

    private void genCastling(int s, List<Move> out) {
        if (white) {
            if (s != 4) return;
            if ((castle & CASTLE_WK) != 0 && sq[5] == 0 && sq[6] == 0 && sq[7] == ROOK
                    && !attacked(4, false) && !attacked(5, false) && !attacked(6, false)) {
                out.add(new Move(4, 6));
            }
            if ((castle & CASTLE_WQ) != 0 && sq[3] == 0 && sq[2] == 0 && sq[1] == 0 && sq[0] == ROOK
                    && !attacked(4, false) && !attacked(3, false) && !attacked(2, false)) {
                out.add(new Move(4, 2));
            }
        } else {
            if (s != 60) return;
            if ((castle & CASTLE_BK) != 0 && sq[61] == 0 && sq[62] == 0 && sq[63] == -ROOK
                    && !attacked(60, true) && !attacked(61, true) && !attacked(62, true)) {
                out.add(new Move(60, 62));
            }
            if ((castle & CASTLE_BQ) != 0 && sq[59] == 0 && sq[58] == 0 && sq[57] == 0 && sq[56] == -ROOK
                    && !attacked(60, true) && !attacked(59, true) && !attacked(58, true)) {
                out.add(new Move(60, 58));
            }
        }
    }

    /** All legal moves for the side to move. */
    public List<Move> legalMoves() {
        List<Move> pseudo = new ArrayList<>(48);
        genPseudo(pseudo);
        List<Move> legal = new ArrayList<>(pseudo.size());
        for (Move m : pseudo) {
            Board nb = play(m);
            // After the move nb.white is the opponent; the mover's king must not be attacked.
            if (!nb.attacked(white ? nb.wk : nb.bk, !white)) legal.add(m);
        }
        return legal;
    }

    public List<Move> legalMovesFrom(int from) {
        List<Move> out = new ArrayList<>();
        for (Move m : legalMoves()) if (m.from == from) out.add(m);
        return out;
    }

    /** Returns the legal move equal to (from, to, promo), or null. */
    public Move findMove(int from, int to, int promo) {
        for (Move m : legalMoves()) if (m.from == from && m.to == to && m.promo == promo) return m;
        return null;
    }

    public Move findMove(Move wanted) {
        return findMove(wanted.from, wanted.to, wanted.promo);
    }

    /** Parses UCI notation ("e2e4", "e7e8q") into a legal move of this position, or null. */
    public Move parseUci(String s) {
        if (s == null || s.length() < 4 || s.length() > 5) return null;
        int from = Move.squareIndex(s.substring(0, 2));
        int to = Move.squareIndex(s.substring(2, 4));
        if (from < 0 || to < 0) return null;
        int promo = 0;
        if (s.length() == 5) {
            promo = "pnbrqk".indexOf(Character.toLowerCase(s.charAt(4))) + 1;
            if (promo < KNIGHT || promo > QUEEN) return null;
        }
        return findMove(from, to, promo);
    }

    /** Applies a move that is known to be legal (or pseudo-legal) and returns the new position. */
    public Board play(Move m) {
        Board b = copy();
        final int from = m.from, to = m.to;
        final int piece = sq[from];
        final int type = Math.abs(piece);
        final boolean w = white;
        final int captured = sq[to];
        final boolean enPassant = type == PAWN && to == ep && captured == EMPTY && (from & 7) != (to & 7);

        b.sq[from] = 0;
        if (enPassant) b.sq[w ? to - 8 : to + 8] = 0;
        b.sq[to] = (byte) (m.promo != 0 ? (w ? m.promo : -m.promo) : piece);

        if (type == KING) {
            if (w) b.wk = to;
            else b.bk = to;
            if (Math.abs(to - from) == 2) {
                if (to > from) {
                    b.sq[from + 1] = b.sq[from + 3];
                    b.sq[from + 3] = 0;
                } else {
                    b.sq[from - 1] = b.sq[from - 4];
                    b.sq[from - 4] = 0;
                }
            }
            b.castle &= w ? ~(CASTLE_WK | CASTLE_WQ) : ~(CASTLE_BK | CASTLE_BQ);
        }
        b.castle &= CASTLE_KEEP[from] & CASTLE_KEEP[to];
        b.ep = (type == PAWN && Math.abs(to - from) == 16) ? (from + to) / 2 : -1;
        b.half = (type == PAWN || captured != EMPTY || enPassant) ? 0 : half + 1;
        b.full = w ? full : full + 1;
        b.white = !w;
        return b;
    }

    // ------------------------------------------------------------------ game state helpers

    public boolean isCheckmate() {
        return inCheck() && legalMoves().isEmpty();
    }

    public boolean isStalemate() {
        return !inCheck() && legalMoves().isEmpty();
    }

    /** True when neither side can possibly deliver mate (K vs K, K+minor vs K, same-coloured bishops). */
    public boolean insufficientMaterial() {
        int minors = 0, knights = 0;
        boolean lightBishop = false, darkBishop = false;
        for (int i = 0; i < 64; i++) {
            int t = Math.abs(sq[i]);
            if (t == EMPTY || t == KING) continue;
            if (t == PAWN || t == ROOK || t == QUEEN) return false;
            minors++;
            if (t == KNIGHT) knights++;
            else if ((((i >> 3) + (i & 7)) & 1) == 0) darkBishop = true;
            else lightBishop = true;
        }
        if (minors <= 1) return true;
        if (knights > 0) return false;
        return !(lightBishop && darkBishop);
    }

    private boolean epCapturePossible() {
        if (ep < 0) return false;
        for (Move m : legalMoves()) {
            if (m.to == ep && Math.abs(sq[m.from]) == PAWN) return true;
        }
        return false;
    }

    /** Key identifying the position for repetition detection (ignores the move counters). */
    public String positionKey() {
        StringBuilder sb = new StringBuilder(72);
        for (int i = 0; i < 64; i++) sb.append(PIECE_KEY_CHARS.charAt(sq[i] + 6));
        sb.append(white ? 'w' : 'b').append((char) ('a' + castle));
        if (ep >= 0 && epCapturePossible()) sb.append(ep);
        return sb.toString();
    }

    // ------------------------------------------------------------------ SAN

    private static char pieceLetter(int type) {
        return "PNBRQK".charAt(type - 1);
    }

    /** Standard algebraic notation of a move that is legal in this position. */
    public String san(Move m) {
        final int piece = sq[m.from];
        final int type = Math.abs(piece);
        StringBuilder sb = new StringBuilder(8);
        if (type == KING && Math.abs(m.to - m.from) == 2) {
            sb.append(m.to > m.from ? "O-O" : "O-O-O");
        } else {
            boolean capture = sq[m.to] != EMPTY || (type == PAWN && (m.from & 7) != (m.to & 7));
            if (type == PAWN) {
                if (capture) sb.append((char) ('a' + (m.from & 7)));
            } else {
                sb.append(pieceLetter(type));
                boolean ambiguous = false, sameFile = false, sameRank = false;
                for (Move o : legalMoves()) {
                    if (o.to == m.to && o.from != m.from && Math.abs(sq[o.from]) == type) {
                        ambiguous = true;
                        if ((o.from & 7) == (m.from & 7)) sameFile = true;
                        if ((o.from >> 3) == (m.from >> 3)) sameRank = true;
                    }
                }
                if (ambiguous) {
                    if (!sameFile) sb.append((char) ('a' + (m.from & 7)));
                    else if (!sameRank) sb.append((char) ('1' + (m.from >> 3)));
                    else sb.append(Move.squareName(m.from));
                }
            }
            if (capture) sb.append('x');
            sb.append(Move.squareName(m.to));
            if (m.promo != 0) sb.append('=').append(pieceLetter(m.promo));
        }
        Board nb = play(m);
        if (nb.inCheck()) sb.append(nb.legalMoves().isEmpty() ? '#' : '+');
        return sb.toString();
    }
}
