package dev.stuckfih.chess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A thin horizontal bar showing how the engine sees the game: White's share on the left. */
public final class EvalBar extends View {
    private final Paint paint = new Paint();
    private float whiteShare = 0.5f;

    public EvalBar(Context context) {
        super(context);
    }

    /** @param share White's winning chances from 0 (lost) to 1 (won) */
    public void setWhiteShare(float share) {
        whiteShare = Math.max(0f, Math.min(1f, share));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        paint.setColor(0xFF000000);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFFF2F2F2);
        canvas.drawRect(0, 0, w * whiteShare, h, paint);
        paint.setColor(0xFF808080);
        canvas.drawRect(w / 2f - 1f, 0, w / 2f + 1f, h, paint);
    }
}
