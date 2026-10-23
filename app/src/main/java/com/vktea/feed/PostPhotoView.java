package com.vktea.feed;

import android.content.Context;
import androidx.appcompat.widget.AppCompatImageView;

/** Uses the post's measured width, retaining the full image even for exceptionally tall photos. */
public final class PostPhotoView extends AppCompatImageView {
    private int sourceWidth = 16, sourceHeight = 9, loadedWidth;
    private WidthRequest widthRequest;
    Object binding;

    interface WidthRequest { void load(int width); }

    public PostPhotoView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
        setBackgroundColor(0xFFEEF1F4);
    }

    void setSourceSize(int width, int height) {
        if (width > 0 && height > 0 && (sourceWidth != width || sourceHeight != height)) {
            sourceWidth = width;
            sourceHeight = height;
            requestLayout();
        }
    }

    void loadAtMeasuredWidth(WidthRequest request) {
        widthRequest = request;
        loadedWidth = 0;
        if (request != null && getWidth() > 0) {
            loadedWidth = getWidth();
            request.load(loadedWidth);
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (widthRequest != null && width > 0 && width != loadedWidth) {
            loadedWidth = width;
            widthRequest.load(width);
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = (int) Math.max(1, Math.min((long) width * 2,
                Math.round(width * (double) sourceHeight / sourceWidth)));
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, MeasureSpec.getSize(heightSpec));
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
