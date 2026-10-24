package com.vktea.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

/** Measures the complete post body at full width, then clips only its feed viewport. */
public final class FeedPostContent extends FrameLayout {
    public interface OverflowListener { void onOverflowChanged(boolean overflowing); }
    private boolean folding, expanded, overflowing;
    private int naturalHeight, heightBudget;
    private OverflowListener listener;
    private RecyclerView list;
    private final OnLayoutChangeListener listLayout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) requestLayout();
    };

    public FeedPostContent(Context context, AttributeSet attrs) { super(context, attrs); }

    /** Only FeedAdapter enables folding; profile walls and opened posts remain unrestricted. */
    public void bindForFeed(boolean expanded, OverflowListener listener) {
        this.folding = true;
        this.expanded = expanded;
        this.listener = listener;
        overflowing = false;
        requestLayout();
    }
    public void setExpanded(boolean expanded) {
        if (this.expanded != expanded) { this.expanded = expanded; requestLayout(); }
    }
    public void reset() {
        folding = expanded = overflowing = false;
        listener = null;
        requestLayout();
    }
    public int getNaturalContentHeight() { return naturalHeight; }
    public int getHeightBudget() { return heightBudget; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        naturalHeight = getPaddingTop() + getPaddingBottom();
        if (getChildCount() > 0) {
            View body = getChildAt(0);
            LayoutParams params = (LayoutParams) body.getLayoutParams();
            int childWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight() - params.leftMargin - params.rightMargin);
            body.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            naturalHeight += body.getMeasuredHeight() + params.topMargin + params.bottomMargin;
        }
        heightBudget = budget();
        boolean nextOverflow = folding && naturalHeight > heightBudget;
        setMeasuredDimension(width, nextOverflow && !expanded ? heightBudget : naturalHeight);
        if (overflowing != nextOverflow) {
            overflowing = nextOverflow;
            if (listener != null) listener.onOverflowChanged(overflowing);
        }
    }
    private int budget() {
        RecyclerView parent = parentList();
        int available = parent == null ? 0 : (parent.getHeight() > 0 ? parent.getHeight() : parent.getMeasuredHeight())
                - parent.getPaddingTop() - parent.getPaddingBottom();
        if (available <= 0) available = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        return Math.max(Math.round(120 * density), Math.min(Math.round(480 * density), Math.round(available * .6f)));
    }
    private RecyclerView parentList() {
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof RecyclerView) return (RecyclerView) parent;
        return null;
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        list = parentList();
        if (list != null) list.addOnLayoutChangeListener(listLayout);
        requestLayout();
    }
    @Override protected void onDetachedFromWindow() {
        if (list != null) list.removeOnLayoutChangeListener(listLayout);
        list = null;
        super.onDetachedFromWindow();
    }
}
