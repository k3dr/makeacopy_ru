package de.schliweb.makeacopy.ui.camera;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import org.opencv.core.Point;

/**
 * A custom view that displays an overlay with corner points, a confidence indicator,
 * and optional labels. The corner overlay is designed to highlight a rectangular region
 * and provides visual feedback via corner markers and edges.
 * <p>
 * This view supports scaling and alignment to ensure the overlay matches the analyzed
 * frame size and provides flexibility to manage display properties such as visibility of
 * corner labels and confidence value.
 */
public class CornerOverlayView extends View {

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int srcW = 0, srcH = 0;

    private Point[] corners = null;

    private float confidence = -1f;
    private boolean showLabels = true;

    public CornerOverlayView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public CornerOverlayView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    public CornerOverlayView(Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(ctx, attrs, defStyleAttr);
        init(ctx);
    }

    private float dp(float d) {
        return d * getResources().getDisplayMetrics().density;
    }

    private void init(Context ctx) {
        setWillNotDraw(false);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(2.0f));

        cornerPaint.setStyle(Paint.Style.FILL);

        labelPaint.setTextSize(dp(12));
        labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        textBgPaint.setStyle(Paint.Style.FILL);

        int edgeColor = Color.WHITE;
        int cornerColor = Color.WHITE;
        int labelColor = Color.WHITE;
        int textBgColor = 0x66000000;

        edgePaint.setColor(edgeColor);
        cornerPaint.setColor(cornerColor);
        labelPaint.setColor(labelColor);
        textBgPaint.setColor(textBgColor);
    }

    /**
     * Sets the source width and height for the view. If the provided dimensions are non-positive or
     * match the current dimensions, the method will return without making any changes. Otherwise,
     * the new dimensions are set, and the view is invalidated to request a redraw.
     *
     * @param w the width of the source, must be greater than 0
     * @param h the height of the source, must be greater than 0
     */
    public void setSourceSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == srcW && h == srcH) return;
        this.srcW = w;
        this.srcH = h;
        postInvalidateOnAnimation();
    }

    /**
     * Sets the corner points for the view. The provided array can define up to four points
     * (top-left, top-right, bottom-right, bottom-left). If the input array is null or does
     * not have exactly four points, the corners will be set to null.
     *
     * @param pts an array of {@code Point} objects representing the corners in the following order:
     *            top-left, top-right, bottom-right, bottom-left. May be null.
     */
    public void setCorners(@Nullable Point[] pts) {
        if (pts == null || pts.length != 4) {
            this.corners = null;
        } else {
            // defensive Kopie
            this.corners = new Point[]{
                    new Point(pts[0].x, pts[0].y),
                    new Point(pts[1].x, pts[1].y),
                    new Point(pts[2].x, pts[2].y),
                    new Point(pts[3].x, pts[3].y)
            };
        }
        postInvalidateOnAnimation();
    }

    /**
     * Sets the confidence value for the view. This value is used to represent the confidence level,
     * which may influence the way the overlay is rendered. Once the confidence is set, the view
     * is invalidated to request a redraw.
     *
     * @param c the confidence value to set, typically a float between 0 and 1
     */
    public void setConfidence(float c) {
        this.confidence = c;
        postInvalidateOnAnimation();
    }

    /**
     * Sets whether labels should be displayed on the overlay.
     * When called, the view is invalidated and redrawn with the updated label visibility state.
     *
     * @param show a boolean indicating whether labels should be shown (true) or hidden (false)
     */
    public void setShowLabels(boolean show) {
        this.showLabels = show;
        postInvalidateOnAnimation();
    }

    /**
     * Clears the current state of the corner overlay view by resetting the corner points
     * and confidence value to their default state. After resetting, the view is invalidated
     * to trigger a redraw.
     * <p>
     * This method sets:
     * - The corner points to null.
     * - The confidence value to -1f.
     */
    public void clear() {
        this.corners = null;
        this.confidence = -1f;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int vw = getWidth();
        final int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        drawConfidence(canvas);

        if (corners == null || srcW <= 0 || srcH <= 0) return;

        float scale = Math.min(vw / (float) srcW, vh / (float) srcH);
        float drawnW = srcW * scale;
        float drawnH = srcH * scale;
        float offX = (vw - drawnW) / 2f;
        float offY = (vh - drawnH) / 2f;

        float r = dp(4);
        String[] labels = new String[]{"TL", "TR", "BR", "BL"};

        Path path = new Path();
        for (int i = 0; i < 4; i++) {
            float x = (float) (corners[i].x * scale + offX);
            float y = (float) (corners[i].y * scale + offY);

            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);

            canvas.drawCircle(x, y, r, cornerPaint);

            if (showLabels) {
                drawLabel(canvas, labels[i], x, y);
            }
        }
        float x0 = (float) (corners[0].x * scale + offX);
        float y0 = (float) (corners[0].y * scale + offY);
        path.lineTo(x0, y0);

        canvas.drawPath(path, edgePaint);
    }

    private void drawLabel(Canvas canvas, String txt, float x, float y) {
        float pad = dp(3);
        float tx = x + dp(6);
        float ty = y - dp(6);
        Rect bounds = new Rect();
        labelPaint.getTextBounds(txt, 0, txt.length(), bounds);
        float bgLeft = tx - pad;
        float bgTop = ty - bounds.height() - pad;
        float bgRight = tx + bounds.width() + pad;
        float bgBottom = ty + pad;
        canvas.drawRoundRect(new RectF(bgLeft, bgTop, bgRight, bgBottom), dp(4), dp(4), textBgPaint);
        canvas.drawText(txt, tx, ty, labelPaint);
    }

    private void drawConfidence(Canvas canvas) {
        if (confidence < 0f) return;
        String txt = String.format("conf: %.0f%%", confidence * 100f);
        float pad = dp(6);
        Rect bounds = new Rect();
        labelPaint.getTextBounds(txt, 0, txt.length(), bounds);
        float bgLeft = pad;
        float bgTop = pad;
        float bgRight = pad + bounds.width() + 2 * pad;
        float bgBottom = pad + bounds.height() + 2 * pad;
        canvas.drawRoundRect(new RectF(bgLeft, bgTop, bgRight, bgBottom), dp(6), dp(6), textBgPaint);
        canvas.drawText(txt, bgLeft + pad, bgBottom - pad, labelPaint);
    }
}
