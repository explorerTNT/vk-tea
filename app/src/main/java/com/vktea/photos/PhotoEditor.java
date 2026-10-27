package com.vktea.photos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Explicit crop/rotation preview. Unmodified uploads retain their original bytes. */
final class PhotoEditor {
    interface Selection { void accept(int rotation, RectF crop); }
    static void show(Fragment fragment, Uri uri, Selection callback) {
        Context context = fragment.requireContext();
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        TextView help = new TextView(context); help.setText("Проведите по фото, чтобы выделить область. Поворот сбрасывает обрезку. Изменённая копия: до 4096 пикселей по длинной стороне. При редактировании GIF анимация заменяется первым кадром PNG."); help.setPadding(20, 12, 20, 12); body.addView(help);
        CropView preview = new CropView(context);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        body.addView(preview, new LinearLayout.LayoutParams(-1, previewHeight(metrics.heightPixels, metrics.density)));
        Button rotate = new Button(context); rotate.setText("Повернуть на 90°"); rotate.setEnabled(false); body.addView(rotate);
        Button reset = new Button(context); reset.setText("Сбросить обрезку"); reset.setEnabled(false); body.addView(reset);
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Подготовить фотографию")
                .setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Загрузить", null).create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> { RectF rect = preview.crop(); int angle = preview.rotation; dialog.dismiss(); callback.accept(angle, rect); });
        });
        rotate.setOnClickListener(v -> preview.rotate()); reset.setOnClickListener(v -> preview.reset());
        dialog.setOnDismissListener(v -> { if (preview.bitmap != null && !preview.bitmap.isRecycled()) { preview.bitmap.recycle(); preview.bitmap = null; } });
        dialog.show();
        new Thread(() -> {
            Bitmap bitmap = null; try { bitmap = decode(context, uri, 1280); } catch (Exception ignored) { }
            Bitmap result = bitmap;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dialog.isShowing() || !fragment.isAdded()) { if (result != null) result.recycle(); return; }
                if (result == null) { help.setText("Не удалось открыть изображение. Выберите файл JPEG, PNG или GIF размером до 25 МБ."); return; }
                preview.bitmap = result; preview.invalidate(); rotate.setEnabled(true); reset.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        }, "photo-preview").start();
    }
    static int previewHeight(int screenPixels, float density) {
        int desired = Math.round(screenPixels * .40f);
        return Math.max(Math.round(120 * density), Math.min(Math.round(320 * density), desired));
    }
    static Bitmap decode(Context context, Uri uri, int maxDimension) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > PhotoWrites.MAX_PHOTO_BYTES) throw new IOException("Too large");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { if (in == null) throw new IOException(); BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException();
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth / options.inSampleSize, bounds.outHeight / options.inSampleSize) > maxDimension) options.inSampleSize *= 2;
        Bitmap bitmap; try (InputStream in = context.getContentResolver().openInputStream(uri)) { if (in == null) throw new IOException(); bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) throw new IOException();
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); } catch (IOException ignored) { }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return bitmap;
        }
        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (oriented != bitmap) bitmap.recycle(); return oriented;
    }
    static File render(Context context, Uri uri, int rotation, RectF crop) throws IOException {
        Bitmap bitmap = decode(context, uri, 4096);
        try {
            if (rotation != 0) {
                Matrix m = new Matrix(); m.setRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated; }
            }
            if (crop != null) {
                Rect area = cropPixels(bitmap.getWidth(), bitmap.getHeight(), crop);
                Bitmap cropped = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height());
                if (cropped != bitmap) { bitmap.recycle(); bitmap = cropped; }
            }
            File dir = new File(context.getCacheDir(), "photo-share"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
            String mime = context.getContentResolver().getType(uri);
            boolean png = "image/png".equalsIgnoreCase(mime) || "image/gif".equalsIgnoreCase(mime);
            File file = File.createTempFile("edit-", png ? ".png" : ".jpg", dir);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException();
            } catch (IOException e) { file.delete(); throw e; }
            if (file.length() > PhotoWrites.MAX_PHOTO_BYTES) { file.delete(); throw new IOException(); }
            return file;
        } finally { bitmap.recycle(); }
    }
    static Rect cropPixels(int width, int height, RectF crop) {
        int x = Math.min(width - 1, Math.max(0, Math.round(crop.left * width)));
        int y = Math.min(height - 1, Math.max(0, Math.round(crop.top * height)));
        int right = Math.min(width, Math.max(x + 1, Math.round(crop.right * width)));
        int bottom = Math.min(height, Math.max(y + 1, Math.round(crop.bottom * height)));
        return new Rect(x, y, right, bottom);
    }
    private static final class CropView extends View {
        Bitmap bitmap; int rotation; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final RectF image = new RectF();
        private RectF selection; private float startX, startY;
        CropView(Context context) { super(context); setBackgroundColor(Color.BLACK); }
        void reset() { selection = null; invalidate(); }
        void rotate() {
            if (bitmap == null) return;
            Matrix matrix = new Matrix(); matrix.setRotate(90);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle(); bitmap = rotated; rotation = (rotation + 90) % 360; reset();
        }
        RectF crop() { return selection == null ? null : new RectF(selection); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (bitmap == null) return;
            float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            image.set((getWidth() - width) / 2, (getHeight() - height) / 2, (getWidth() + width) / 2, (getHeight() + height) / 2);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL); canvas.drawBitmap(bitmap, null, image, paint);
            if (selection != null) {
                RectF r = new RectF(image.left + selection.left * width, image.top + selection.top * height, image.left + selection.right * width, image.top + selection.bottom * height);
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); canvas.drawRect(r, paint); paint.setStyle(Paint.Style.FILL);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null || image.isEmpty()) return false;
            float x = Math.max(0, Math.min(1, (event.getX() - image.left) / image.width()));
            float y = Math.max(0, Math.min(1, (event.getY() - image.top) / image.height()));
            if (event.getAction() == MotionEvent.ACTION_DOWN) { getParent().requestDisallowInterceptTouchEvent(true); startX = x; startY = y; return true; }
            if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) {
                selection = Math.abs(x - startX) < .02f || Math.abs(y - startY) < .02f ? null : new RectF(Math.min(x, startX), Math.min(y, startY), Math.max(x, startX), Math.max(y, startY));
                invalidate(); if (event.getAction() == MotionEvent.ACTION_UP) { getParent().requestDisallowInterceptTouchEvent(false); performClick(); } return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
