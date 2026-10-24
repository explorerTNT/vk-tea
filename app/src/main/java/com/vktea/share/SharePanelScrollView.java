package com.vktea.share;

import android.content.Context;
import android.util.AttributeSet;
import androidx.core.widget.NestedScrollView;

/** Wraps compact content and scrolls only when the sheet reaches the usable window height. */
public final class SharePanelScrollView extends NestedScrollView {
    private int maximumHeight = Integer.MAX_VALUE;
    public SharePanelScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    void setMaximumHeight(int height) {
        int next = Math.max(1, height);
        if (maximumHeight != next) { maximumHeight = next; requestLayout(); }
    }
    @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? maximumHeight
                : Math.min(maximumHeight, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
    }
}
