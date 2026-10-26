package com.vktea.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.core.widget.NestedScrollView;

/** Keeps the artwork/body seam seekable and arbitrates downward collapse against controls. */
public final class AudioPlayerLayout extends LinearLayout {
    private final int slop, maximumVelocity;
    private final float flingVelocity, flingDistance;
    private SeekBar seek;
    private NestedScrollView body;
    private Runnable collapse;
    private VelocityTracker velocity;
    private float downX, downY, distance;
    private boolean eligible, dragging, closing, seekStream, seekCancelled;

    public AudioPlayerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop();
        maximumVelocity = config.getScaledMaximumFlingVelocity();
        float density = getResources().getDisplayMetrics().density;
        flingVelocity = 1000 * density;
        flingDistance = Math.max(slop * 3f, 32 * density);
    }

    void bind(SeekBar seek, NestedScrollView body, Runnable collapse) {
        this.seek = seek;
        this.body = body;
        this.collapse = collapse;
    }

    boolean isSeekTouchCancelled() { return seekCancelled; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetGesture();
            downX = event.getRawX();
            downY = event.getRawY();
            seekCancelled = false;
            // The 48dp seek hitbox straddles the cover/scroll boundary. Drawing outside a
            // scroll parent does not extend its hitbox, so this root owns the whole stream.
            boolean control = hitsControl(this, downX, downY);
            boolean seekHit = seek != null && seek.isShown() && containsUnclipped(seek, downX, downY);
            seekStream = !control && seekHit && seek.isEnabled();
            eligible = !control && !seekHit && collapse != null
                    && (body == null || !containsUnclipped(body, downX, downY)
                    || !body.canScrollVertically(-1));
            if (eligible) velocity = VelocityTracker.obtain();
        }
        if (seekStream) {
            if (!seekCancelled && seek.isEnabled()) {
                boolean cancel = action == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1;
                seekCancelled = cancel;
                dispatchToSeek(event, cancel);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) seekStream = false;
            return true;
        }
        if (event.getPointerCount() > 1) {
            eligible = false;
            if (dragging) restore();
        }
        if (velocity != null) {
            // Raw coordinates remain stable while this layout follows the finger.
            MotionEvent raw = MotionEvent.obtain(event);
            raw.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
            velocity.addMovement(raw);
            raw.recycle();
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            eligible = false;
            recycleVelocity();
        }
        return handled;
    }

    private void dispatchToSeek(MotionEvent event, boolean cancel) {
        int[] origin = new int[2];
        seek.getLocationOnScreen(origin);
        MotionEvent local = MotionEvent.obtain(event);
        local.offsetLocation(event.getRawX() - origin[0] - event.getX(),
                event.getRawY() - origin[1] - event.getY());
        if (cancel) local.setAction(MotionEvent.ACTION_CANCEL);
        seek.dispatchTouchEvent(local);
        local.recycle();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (dragging) return true;
        if (!eligible || event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
        if ((Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) || dy < -slop) eligible = false;
        if (!eligible || dy <= slop || dy <= Math.abs(dx) * 1.25f) return false;
        dragging = true;
        translate(dy);
        return true;
    }

    @SuppressLint("ClickableViewAccessibility") // Buttons and SeekBar keep their own accessible actions.
    @Override public boolean onTouchEvent(MotionEvent event) {
        // Artwork/empty space can target this layout itself; ViewGroup then skips
        // onInterceptTouchEvent on subsequent MOVE events because no child owns DOWN.
        if (!dragging && eligible) onInterceptTouchEvent(event);
        if (!dragging) return eligible || super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) restore();
        else if (action == MotionEvent.ACTION_MOVE) translate(event.getRawY() - downY);
        else if (action == MotionEvent.ACTION_UP) {
            translate(event.getRawY() - downY);
            velocity.computeCurrentVelocity(1000, maximumVelocity);
            boolean fling = distance >= flingDistance && velocity.getYVelocity() >= flingVelocity
                    && velocity.getYVelocity() > Math.abs(velocity.getXVelocity()) * 1.25f;
            if (distance > getHeight() / 4f || fling) {
                dragging = false;
                closing = true;
                animate().translationY(getHeight()).setDuration(140)
                        .withEndAction(() -> { if (closing && collapse != null) collapse.run(); }).start();
            } else restore();
        }
        return true;
    }

    private void translate(float dy) {
        distance = Math.max(0, dy);
        setTranslationY(distance);
    }

    private void restore() {
        dragging = eligible = false;
        animate().translationY(0).setDuration(160).start();
    }

    void resetGesture() {
        if (seekStream && !seekCancelled && seek != null) {
            seekCancelled = true;
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            seek.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        animate().cancel();
        setTranslationY(0);
        eligible = dragging = closing = seekStream = false;
        distance = 0;
        recycleVelocity();
    }

    private static boolean containsUnclipped(View view, float x, float y) {
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        return x >= origin[0] && x < origin[0] + view.getWidth()
                && y >= origin[1] && y < origin[1] + view.getHeight();
    }

    private static boolean hitsControl(View view, float x, float y) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible) || !visible.contains((int) x, (int) y)) return false;
        if (!(view instanceof SeekBar) && (view.isClickable() || view.isLongClickable())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--)
                if (hitsControl(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    @Override protected void onDetachedFromWindow() {
        resetGesture();
        super.onDetachedFromWindow();
    }
}
