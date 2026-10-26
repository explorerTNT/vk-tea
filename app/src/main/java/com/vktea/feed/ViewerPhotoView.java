package com.vktea.feed;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import com.github.chrisbanes.photoview.PhotoView;

/** PhotoView resets its supplemental matrix on layout; keep the viewed image point on chrome resize. */
public final class ViewerPhotoView extends PhotoView {
    private float savedScale, centerX, centerY;
    private Drawable savedDrawable;
    private boolean restoring;

    public ViewerPhotoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // The PhotoView attacher's own listener is registered first and has fitted the new viewport.
        addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> restoreViewport());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        savedScale = 0;
        Drawable drawable = getDrawable();
        int oldContentWidth = oldWidth - getPaddingLeft() - getPaddingRight();
        int oldContentHeight = oldHeight - getPaddingTop() - getPaddingBottom();
        if (drawable != null && oldContentWidth > 0 && oldContentHeight > 0
                && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0
                && getScale() > getMinimumScale() + .01f) {
            Matrix inverse = new Matrix();
            Matrix display = new Matrix();
            getDisplayMatrix(display);
            if (display.invert(inverse)) {
                float[] point = {oldContentWidth / 2f, oldContentHeight / 2f};
                inverse.mapPoints(point);
                centerX = point[0] / drawable.getIntrinsicWidth();
                centerY = point[1] / drawable.getIntrinsicHeight();
                savedScale = getScale();
                savedDrawable = drawable;
            }
        }
        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    boolean isPreservingZoom() { return savedScale > 0 || restoring; }

    private void restoreViewport() {
        float scale = savedScale;
        savedScale = 0;
        Drawable drawable = savedDrawable;
        savedDrawable = null;
        if (scale <= 0 || drawable == null || drawable != getDrawable()) return;
        restoring = true;
        try {
            float x = (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
            float y = (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
            setScale(Math.max(getMinimumScale(), Math.min(getMaximumScale(), scale)), x, y, false);
            Matrix display = new Matrix();
            getDisplayMatrix(display);
            float[] point = {centerX * drawable.getIntrinsicWidth(), centerY * drawable.getIntrinsicHeight()};
            display.mapPoints(point);
            Matrix supplement = new Matrix();
            getSuppMatrix(supplement);
            supplement.postTranslate(x - point[0], y - point[1]);
            // PhotoView clamps translation to valid image edges when the new aspect ratio requires it.
            setSuppMatrix(supplement);
        } finally {
            restoring = false;
        }
    }
}
