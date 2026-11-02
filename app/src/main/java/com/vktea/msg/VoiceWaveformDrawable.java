package com.vktea.msg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.vktea.R;

/** Draws only supplied audio peaks; a plain line is used when they are unavailable. */
final class VoiceWaveformDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] peaks;
    private final int trackColor;
    private final int progressColor;
    private final float density;

    VoiceWaveformDrawable(Context context, int[] values) {
        density = context.getResources().getDisplayMetrics().density;
        trackColor = ContextCompat.getColor(context, R.color.post_time);
        progressColor = ContextCompat.getColor(context, R.color.vk_blue);
        peaks = values == null ? new int[0] : values.clone();
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        drawTrack(canvas, bounds, trackColor);
        int save = canvas.save();
        canvas.clipRect(bounds.left, bounds.top,
                bounds.left + bounds.width() * Math.max(0, Math.min(10000, getLevel())) / 10000f,
                bounds.bottom);
        drawTrack(canvas, bounds, progressColor);
        canvas.restoreToCount(save);
    }

    private void drawTrack(Canvas canvas, Rect bounds, int color) {
        paint.setColor(color);
        float center = bounds.exactCenterY();
        if (peaks.length == 0) {
            paint.setStrokeWidth(2 * density);
            canvas.drawLine(bounds.left, center, bounds.right, center, paint);
            return;
        }
        int count = Math.min(peaks.length, Math.max(1, (int) (bounds.width() / (3 * density))));
        float spacing = bounds.width() / (float) count;
        paint.setStrokeWidth(Math.max(density, Math.min(2 * density, spacing * .55f)));
        float maximumHeight = Math.min(bounds.height(), 24 * density);
        for (int i = 0; i < count; i++) {
            int start = (int) ((long) i * peaks.length / count);
            int end = (int) ((long) (i + 1) * peaks.length / count);
            int peak = 0;
            for (int j = start; j < end; j++) peak = Math.max(peak, peaks[j]);
            float height = Math.max(2 * density, maximumHeight * Math.min(31, peak) / 31f);
            float x = bounds.left + (i + .5f) * spacing;
            canvas.drawLine(x, center - height / 2, x, center + height / 2, paint);
        }
    }

    @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicHeight() { return Math.round(24 * density); }
}
