package dev.stuckfih.chess.core;

/**
 * A chess move. Squares are numbered 0..63 with a1 = 0, b1 = 1, ... h1 = 7, a2 = 8, ... h8 = 63.
 * Castling is encoded as the king moving two squares (e1g1), exactly like UCI does.
 */
public final class Move {
    public final int from;
    public final int to;
    /** 0 when the move is not a promotion, otherwise Board.KNIGHT .. Board.QUEEN. */
    public final int promo;

    public Move(int from, int to, int promo) {
        this.from = from;
        this.to = to;
        this.promo = promo;
    }

    public Move(int from, int to) {
        this(from, to, 0);
    }

    public static String squareName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3));
    }

    /** "e4" -> 28, anything invalid -> -1. */
    public static int squareIndex(String s) {
        if (s == null || s.length() < 2) return -1;
        int f = s.charAt(0) - 'a';
        int r = s.charAt(1) - '1';
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return r * 8 + f;
    }

    /** UCI notation such as "e2e4" or "e7e8q". */
    public String uci() {
        StringBuilder sb = new StringBuilder(5);
        sb.append(squareName(from)).append(squareName(to));
        if (promo != 0) sb.append("pnbrqk".charAt(promo - 1));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Move)) return false;
        Move m = (Move) o;
        return from == m.from && to == m.to && promo == m.promo;
    }

    @Override
    public int hashCode() {
        return from | (to << 6) | (promo << 12);
    }

    @Override
    public String toString() {
        return uci();
    }
}
