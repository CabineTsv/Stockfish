package dev.stuckfih.chess;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

import dev.stuckfih.chess.core.Board;
import dev.stuckfih.chess.core.Move;

/**
 * Draws the chess board and turns touches (tap-tap or drag and drop) into moves.
 * The view only knows the current position; the activity owns the game.
 */
public final class BoardView extends View {
    public interface Listener {
        void onMove(Move move);

        /** A pawn move from..to needs a promotion piece; the listener should ask and call back. */
        void onPromotion(int from, int to);
    }

    private static final int LIGHT = 0xFFF0D9B5;
    private static final int DARK = 0xFFB58863;
    private static final int LAST_MOVE = 0x80CDD26A;
    private static final int SELECTED = 0x9914A3FF;
    private static final int HINT = 0xCC1E88E5;

    private static final int[] WHITE_RES = {0, R.drawable.p_wp, R.drawable.p_wn, R.drawable.p_wb,
            R.drawable.p_wr, R.drawable.p_wq, R.drawable.p_wk};
    private static final int[] BLACK_RES = {0, R.drawable.p_bp, R.drawable.p_bn, R.drawable.p_bb,
            R.drawable.p_br, R.drawable.p_bq, R.drawable.p_bk};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Bitmap[] pieces = new Bitmap[13];
    private final boolean[] targets = new boolean[64];
    private final float touchSlop;

    private Listener listener;
    private Board board = Board.startPosition();
    private List<Move> legal = board.legalMoves();
    private Move lastMove;
    private Move hint;
    private boolean flipped;
    private boolean interactive;
    private float sq;

    private int selected = -1;
    private int dragFrom = -1;
    private boolean dragging;
    private boolean wasSelectedOnDown;
    private float downX, downY, dragX, dragY;

    public BoardView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Shows a position. Selection, drag and hint are cleared. */
    public void setPosition(Board b, Move last, boolean canMove) {
        board = b;
        legal = b.legalMoves();
        lastMove = last;
        interactive = canMove;
        hint = null;
        selected = -1;
        dragFrom = -1;
        dragging = false;
        refreshTargets();
        invalidate();
    }

    public void setInteractive(boolean canMove) {
        interactive = canMove;
        if (!canMove) {
            selected = -1;
            dragFrom = -1;
            dragging = false;
            refreshTargets();
        }
        invalidate();
    }

    public void setFlipped(boolean f) {
        flipped = f;
        invalidate();
    }

    public boolean isFlipped() {
        return flipped;
    }

    public void setHint(Move m) {
        hint = m;
        invalidate();
    }

    public void clearHint() {
        if (hint != null) {
            hint = null;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        sq = Math.min(w, h) / 8f;
        int size = Math.max(1, Math.round(sq * 0.94f));
        for (int type = Board.PAWN; type <= Board.KING; type++) {
            pieces[6 + type] = render(WHITE_RES[type], size);
            pieces[6 - type] = render(BLACK_RES[type], size);
        }
    }

    private Bitmap render(int resId, int size) {
        Drawable d = getResources().getDrawable(resId, getContext().getTheme());
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }

    // ------------------------------------------------------------------ geometry

    private float left(int square) {
        int file = square & 7;
        return (flipped ? 7 - file : file) * sq;
    }

    private float top(int square) {
        int rank = square >> 3;
        return (flipped ? rank : 7 - rank) * sq;
    }

    private int squareAt(float x, float y) {
        if (sq <= 0 || x < 0 || y < 0) return -1;
        int col = (int) (x / sq);
        int row = (int) (y / sq);
        if (col > 7 || row > 7) return -1;
        int file = flipped ? 7 - col : col;
        int rank = flipped ? row : 7 - row;
        return rank * 8 + file;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (sq <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        for (int s = 0; s < 64; s++) {
            boolean light = (((s & 7) + (s >> 3)) & 1) == 1;
            paint.setColor(light ? LIGHT : DARK);
            c.drawRect(left(s), top(s), left(s) + sq, top(s) + sq, paint);
        }
        if (lastMove != null) {
            fillSquare(c, lastMove.from, LAST_MOVE);
            fillSquare(c, lastMove.to, LAST_MOVE);
        }
        if (selected >= 0) fillSquare(c, selected, SELECTED);
        if (board.inCheck()) drawCheck(c, board.kingSquare(board.whiteToMove()));
        drawCoordinates(c);
        drawTargets(c);
        if (hint != null) drawArrow(c, hint.from, hint.to);

        for (int s = 0; s < 64; s++) {
            int p = board.pieceAt(s);
            if (p == 0 || (dragging && s == dragFrom)) continue;
            drawPiece(c, p, left(s), top(s));
        }
        if (dragging && dragFrom >= 0) {
            Bitmap b = pieces[board.pieceAt(dragFrom) + 6];
            if (b != null) c.drawBitmap(b, dragX - b.getWidth() / 2f, dragY - b.getHeight() / 2f - sq * 0.3f, bitmapPaint);
        }
    }

    private void fillSquare(Canvas c, int s, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRect(left(s), top(s), left(s) + sq, top(s) + sq, paint);
    }

    private void drawPiece(Canvas c, int piece, float x, float y) {
        Bitmap b = pieces[piece + 6];
        if (b == null) return;
        float pad = (sq - b.getWidth()) / 2f;
        c.drawBitmap(b, x + pad, y + pad, bitmapPaint);
    }

    private void drawCheck(Canvas c, int kingSquare) {
        float cx = left(kingSquare) + sq / 2f, cy = top(kingSquare) + sq / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, sq * 0.62f,
                new int[]{0xFFFF1744, 0xCCE53935, 0x00E53935}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, sq * 0.62f, paint);
        paint.setShader(null);
    }

    private void drawCoordinates(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sq * 0.2f);
        float pad = sq * 0.06f;
        int bottomRank = flipped ? 7 : 0;
        int leftFile = flipped ? 7 : 0;
        for (int i = 0; i < 8; i++) {
            // file letters along the bottom edge
            int file = flipped ? 7 - i : i;
            int bottomSquare = bottomRank * 8 + file;
            boolean lightBottom = (((bottomSquare & 7) + (bottomSquare >> 3)) & 1) == 1;
            paint.setColor(lightBottom ? DARK : LIGHT);
            String letter = String.valueOf((char) ('a' + file));
            float tw = paint.measureText(letter);
            c.drawText(letter, i * sq + sq - tw - pad, 8 * sq - pad, paint);

            // rank numbers down the left edge
            int rank = flipped ? i : 7 - i;
            int leftSquare = rank * 8 + leftFile;
            boolean lightLeft = (((leftSquare & 7) + (leftSquare >> 3)) & 1) == 1;
            paint.setColor(lightLeft ? DARK : LIGHT);
            c.drawText(String.valueOf(rank + 1), pad, i * sq + sq * 0.2f + pad, paint);
        }
    }

    private void drawTargets(Canvas c) {
        if (selected < 0) return;
        for (int s = 0; s < 64; s++) {
            if (!targets[s]) continue;
            float cx = left(s) + sq / 2f, cy = top(s) + sq / 2f;
            if (board.pieceAt(s) != 0 || isEnPassantTarget(s)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sq * 0.09f);
                paint.setColor(0x66000000);
                c.drawCircle(cx, cy, sq * 0.44f, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x55000000);
                c.drawCircle(cx, cy, sq * 0.15f, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private boolean isEnPassantTarget(int s) {
        return s == board.epSquare() && Math.abs(board.pieceAt(selected)) == Board.PAWN;
    }

    private void drawArrow(Canvas c, int from, int to) {
        float x1 = left(from) + sq / 2f, y1 = top(from) + sq / 2f;
        float x2 = left(to) + sq / 2f, y2 = top(to) + sq / 2f;
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float ux = dx / len, uy = dy / len;
        float head = sq * 0.38f;
        float bx = x2 - ux * head, by = y2 - uy * head;
        paint.setColor(HINT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(sq * 0.14f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x1, y1, bx, by, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(x2, y2);
        arrow.lineTo(bx - uy * head * 0.55f, by + ux * head * 0.55f);
        arrow.lineTo(bx + uy * head * 0.55f, by - ux * head * 0.55f);
        arrow.close();
        c.drawPath(arrow, paint);
    }

    // ------------------------------------------------------------------ touch

    private boolean ownPiece(int s) {
        int p = board.pieceAt(s);
        return p != 0 && (p > 0) == board.whiteToMove();
    }

    private boolean isLegalTarget(int from, int to) {
        for (Move m : legal) if (m.from == from && m.to == to) return true;
        return false;
    }

    private void refreshTargets() {
        java.util.Arrays.fill(targets, false);
        if (selected < 0) return;
        for (Move m : legal) if (m.from == selected) targets[m.to] = true;
    }

    private void commit(int from, int to) {
        selected = -1;
        dragFrom = -1;
        dragging = false;
        refreshTargets();
        invalidate();
        if (listener == null) return;
        for (Move m : legal) {
            if (m.from == from && m.to == to && m.promo != 0) {
                listener.onPromotion(from, to);
                return;
            }
        }
        listener.onMove(new Move(from, to));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        int s = squareAt(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                dragFrom = -1;
                wasSelectedOnDown = false;
                if (s >= 0) {
                    if (selected >= 0 && isLegalTarget(selected, s)) {
                        commit(selected, s);
                        return true;
                    }
                    if (ownPiece(s)) {
                        wasSelectedOnDown = s == selected;
                        selected = s;
                        dragFrom = s;
                        dragX = e.getX();
                        dragY = e.getY();
                        refreshTargets();
                        invalidate();
                        return true;
                    }
                }
                selected = -1;
                refreshTargets();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragFrom >= 0) {
                    if (!dragging) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (dx * dx + dy * dy > touchSlop * touchSlop) dragging = true;
                    }
                    if (dragging) {
                        dragX = e.getX();
                        dragY = e.getY();
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && dragFrom >= 0) {
                    int from = dragFrom;
                    dragging = false;
                    dragFrom = -1;
                    if (s >= 0 && s != from && isLegalTarget(from, s)) {
                        commit(from, s);
                    } else {
                        invalidate(); // snap back, keep the piece selected
                    }
                } else {
                    dragFrom = -1;
                    if (wasSelectedOnDown && s == selected) {
                        selected = -1; // tapping the selected piece again deselects it
                        refreshTargets();
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                dragFrom = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
