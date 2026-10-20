package app.enhancedgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;

/** ImageView with matrix zoom + multi-way navigation.
 *  Navigation gestures are active ONLY when not zoomed (scale == 1).
 *  When zoomed, touches pan / pinch-zoom instead. */
public class ZoomableImageView extends ImageView {

    public interface Listener {
        void onNext();
        void onPrev();
        void onToggleInfo();
    }

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float scale = MIN_SCALE; // zoom multiplier on top of the fit transform
    private int drawableW, drawableH;

    private final GestureDetector tap;
    private final ScaleGestureDetector pinch;
    private Listener listener;

    private float lastX, lastY;
    private boolean panning;
    private float flingThresholdPx;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        flingThresholdPx = 120 * context.getResources().getDisplayMetrics().density;

        tap = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener == null || scale > MIN_SCALE + 0.001f) return true;
                float x = e.getX(), y = e.getY();
                int w = getWidth(), h = getHeight();
                if (x < w * 0.35f) listener.onPrev();
                else if (x > w * 0.65f) listener.onNext();
                else if (y < h * 0.20f) listener.onPrev();
                else if (y > h * 0.80f) listener.onNext();
                else listener.onToggleInfo();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (listener == null) return true;
                if (scale <= MIN_SCALE + 0.001f) {
                    zoomTo(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                } else {
                    zoomTo(MIN_SCALE, getWidth() / 2f, getHeight() / 2f);
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                             float velocityX, float velocityY) {
                if (listener == null || scale > MIN_SCALE + 0.001f) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (Math.abs(dx) > flingThresholdPx) {
                        if (dx < 0) listener.onNext(); else listener.onPrev();
                        return true;
                    }
                } else {
                    if (Math.abs(dy) > flingThresholdPx) {
                        if (dy < 0) listener.onNext(); else listener.onPrev();
                        return true;
                    }
                }
                return false;
            }

            @Override public boolean onDown(MotionEvent e) { return true; }
        });

        pinch = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        float target = scale * factor;
                        if (target < MIN_SCALE) target = MIN_SCALE;
                        if (target > MAX_SCALE) target = MAX_SCALE;
                        float applied = target / scale;
                        if (Math.abs(applied - 1f) > 0.0001f) {
                            matrix.postScale(applied, applied, d.getFocusX(), d.getFocusY());
                            scale = target;
                            if (scale <= MIN_SCALE + 0.001f) fitCenter();
                            else fixBounds();
                            setImageMatrix(matrix);
                        }
                        return true;
                    }
                });

        setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                pinch.onTouchEvent(e);
                tap.onTouchEvent(e);
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    lastX = e.getX(); lastY = e.getY(); panning = false;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (scale > MIN_SCALE + 0.001f && !pinch.isInProgress()
                            && e.getPointerCount() == 1) {
                        float dx = e.getX() - lastX, dy = e.getY() - lastY;
                        if (!panning && Math.hypot(dx, dy) > 4) panning = true;
                        if (panning) {
                            matrix.postTranslate(dx, dy);
                            fixBounds();
                            setImageMatrix(matrix);
                        }
                        lastX = e.getX(); lastY = e.getY();
                    }
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    panning = false;
                }
                return true;
            }
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    public float getZoom() { return scale; }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        if (bm != null) {
            drawableW = bm.getWidth();
            drawableH = bm.getHeight();
        }
        fitCenter();
    }

    private void zoomTo(float target, float px, float py) {
        float applied = target / scale;
        matrix.postScale(applied, applied, px, py);
        scale = target;
        if (scale <= MIN_SCALE + 0.001f) fitCenter();
        else fixBounds();
        setImageMatrix(matrix);
    }

    private void fitCenter() {
        scale = MIN_SCALE;
        matrix.reset();
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || drawableW == 0 || drawableH == 0) {
            setImageMatrix(matrix);
            return;
        }
        float s = Math.min((float) w / drawableW, (float) h / drawableH);
        matrix.postScale(s, s);
        matrix.postTranslate((w - drawableW * s) / 2f, (h - drawableH * s) / 2f);
        setImageMatrix(matrix);
    }

    private void fixBounds() {
        if (drawableW == 0) return;
        matrix.getValues(vals);
        float cur = vals[Matrix.MSCALE_X];
        RectF r = new RectF(0, 0, drawableW, drawableH);
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        int w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (scale <= MIN_SCALE + 0.001f) fitCenter();
    }
}
