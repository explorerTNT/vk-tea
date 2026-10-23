package com.vktea.msg;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.vktea.R;

/** Bounds bubbles by the actual row width, including avatar and parent insets. */
public final class ChatMessageRow extends LinearLayout {
    private int usableWidth;

    public ChatMessageRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        usableWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        View bubble = findViewById(R.id.msg_wrap);
        if (bubble instanceof ViewGroup) {
            setPreviewWidths((ViewGroup) bubble,
                    Math.min(dp(280), Math.round(MeasureSpec.getSize(widthSpec) * 0.57f)));
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthSpec, int widthUsed,
                                          int parentHeightSpec, int heightUsed) {
        if (child.getId() != R.id.msg_wrap) {
            super.measureChildWithMargins(child, parentWidthSpec, widthUsed, parentHeightSpec, heightUsed);
            return;
        }
        MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
        int remaining = Math.max(0, usableWidth - widthUsed - params.leftMargin - params.rightMargin);
        int maximum = Math.min(remaining, Math.min(dp(360), Math.round(usableWidth * 0.86f)));
        int heightSpec = getChildMeasureSpec(parentHeightSpec,
                getPaddingTop() + getPaddingBottom() + params.topMargin + params.bottomMargin + heightUsed,
                params.height);
        child.measure(MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST), heightSpec);
    }

    private void setPreviewWidths(ViewGroup parent, int width) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ChatMediaPreview) ((ChatMediaPreview) child).setPreferredWidth(width);
            else if (child instanceof ViewGroup) setPreviewWidths((ViewGroup) child, width);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
