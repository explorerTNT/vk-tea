package com.vktea.msg;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Fits the full selected image aspect ratio; tall media is scaled down without cropping. */
public final class ChatMediaPreview extends FrameLayout {
    private int sourceWidth = 16;
    private int sourceHeight = 9;
    private int preferredWidth;

    public ChatMediaPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipToOutline(true);
    }

    void setPreferredWidth(int width) {
        preferredWidth = Math.max(1, width);
    }

    public void setSourceDimensions(int width, int height) {
        if (width > 0 && height > 0 && (sourceWidth != width || sourceHeight != height)) {
            sourceWidth = width;
            sourceHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int target = preferredWidth > 0 ? preferredWidth : dp(215);
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? target
                : Math.min(target, MeasureSpec.getSize(widthSpec));
        int height = Math.max(1, (int) Math.round(width * (double) sourceHeight / sourceWidth));
        int maximumHeight = dp(420);
        if (height > maximumHeight) {
            height = maximumHeight;
            width = Math.max(1, (int) Math.round(height * (double) sourceWidth / sourceHeight));
        }
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.AT_MOST && height > MeasureSpec.getSize(heightSpec)) {
            height = MeasureSpec.getSize(heightSpec);
            width = Math.max(1, (int) Math.round(height * (double) sourceWidth / sourceHeight));
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, height), MeasureSpec.EXACTLY));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
