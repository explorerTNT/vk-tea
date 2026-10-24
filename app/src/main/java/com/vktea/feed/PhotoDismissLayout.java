package com.vktea.feed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Arbitrates a one-finger vertical dismiss against horizontal paging and PhotoView pan/pinch. */
public final class PhotoDismissLayout extends FrameLayout {
    public interface Listener {
        boolean canDismiss();
        void onProgress(float fraction);
        void onDismiss();
        default void onMultiTouch() { }
        default void onTouchEnd() { }
    }

    private final int slop, maximumVelocity;
    private final float flingVelocity, flingDistance;
    private Listener listener;
    private View target;
    private VelocityTracker velocity;
    private float downX, downY, distance;
    private boolean dragging, horizontal, multiplePointers, closing, animating, eligible, touching;

    public PhotoDismissLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop();
        maximumVelocity = config.getScaledMaximumFlingVelocity();
        float density = getResources().getDisplayMetrics().density;
        flingVelocity = density * 1000f;
        flingDistance = Math.max(slop * 3f, density * 32f);
    }

    public void bind(View target, Listener listener) { this.target = target; this.listener = listener; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            downX = event.getX();
            downY = event.getY();
            eligible = listener != null && listener.canDismiss();
            multiplePointers = horizontal = false;
            touching = true;
            velocity = VelocityTracker.obtain();
        }
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
            // Disable the pager before its RecyclerView can mistake the first finger's pinch motion for a swipe.
            if (!multiplePointers && listener != null) listener.onMultiTouch();
            multiplePointers = true;
            eligible = false;
        }
        if (velocity != null) velocity.addMovement(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            recycleVelocity();
            if (!dragging && !animating && !closing && listener != null) listener.onTouchEnd();
        }
        return handled;
    }

    public boolean isMultiTouchActive() { return touching && multiplePointers; }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (dragging || animating) return true;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE || !allowed()) return false;
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.25f) horizontal = true;
        if (horizontal || Math.abs(dy) <= slop || Math.abs(dy) <= Math.abs(dx) * 1.25f) return false;
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        translate(dy);
        return true;
    }

    private boolean allowed() {
        return eligible && !multiplePointers && listener != null && listener.canDismiss() && target != null;
    }

    // PhotoView disallows interception on DOWN. At fit scale this parent must still arbitrate the axis.
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        super.requestDisallowInterceptTouchEvent(disallow && !allowed());
    }

    // This parent consumes drags only. PhotoView handles taps; the toolbar has an accessible Back button.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return animating || super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (multiplePointers || !allowed() || action == MotionEvent.ACTION_CANCEL) {
            returnToPhoto();
        } else if (action == MotionEvent.ACTION_MOVE) {
            translate(event.getY() - downY);
        } else if (action == MotionEvent.ACTION_UP) {
            float vy = 0, vx = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000, maximumVelocity);
                vy = velocity.getYVelocity();
                vx = velocity.getXVelocity();
            }
            boolean fling = Math.abs(distance) >= flingDistance && Math.abs(vy) >= flingVelocity
                    && Math.abs(vy) > Math.abs(vx) * 1.25f && Math.signum(vy) == Math.signum(distance);
            if (Math.abs(distance) > getHeight() / 4f || fling) dismiss();
            else returnToPhoto();
        }
        return true;
    }

    private void translate(float dy) {
        distance = dy;
        float progress = Math.min(1f, Math.abs(dy) / Math.max(1f, getHeight() / 2f));
        target.setTranslationY(dy);
        target.setAlpha(1f - progress * .45f);
        listener.onProgress(progress);
    }

    private void dismiss() {
        dragging = false;
        closing = true;
        target.animate().translationY(Math.copySign(getHeight(), distance)).alpha(0f).setDuration(140)
                .withEndAction(() -> { if (closing && listener != null) listener.onDismiss(); }).start();
    }

    private void returnToPhoto() {
        dragging = false;
        eligible = false;
        animating = true;
        target.animate().translationY(0f).alpha(1f).setDuration(160).withEndAction(() -> {
            animating = false;
            distance = 0;
            if (listener != null) listener.onProgress(0f);
        }).start();
    }

    public void reset() {
        if (target != null) {
            target.animate().cancel();
            target.setTranslationY(0f);
            target.setAlpha(1f);
        }
        dragging = closing = animating = touching = false;
        distance = 0;
        recycleVelocity();
        if (listener != null) listener.onProgress(0f);
    }

    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}
