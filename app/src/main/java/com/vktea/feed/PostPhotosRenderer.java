package com.vktea.feed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.List;

/** Shared wall/feed/detail photo rendering. Other attachment cards and avatars are independent. */
public final class PostPhotosRenderer {
    interface SizeReady { void onSize(int width, int height); }
    interface Images {
        void load(PostPhotoView target, String url, SizeReady ready);
        void clear(ImageView target);
    }
    private static final Images GLIDE = new GlideImages();
    private PostPhotosRenderer() { }

    public static void render(LinearLayout container, List<String> urls) {
        render(container, urls, null, GLIDE);
    }

    public static void render(LinearLayout container, List<String> urls, List<ViewerPhoto> details) {
        render(container, urls, details, GLIDE);
    }

    static void render(LinearLayout container, List<String> urls, Images images) {
        render(container, urls, null, images);
    }

    static void render(LinearLayout container, List<String> urls, List<ViewerPhoto> details, Images images) {
        clear(container, images);
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        ArrayList<String> photos = new ArrayList<>();
        ArrayList<ViewerPhoto> metadata = new ArrayList<>();
        if (urls != null) for (int index = 0; index < urls.size(); index++) {
            String url = urls.get(index);
            if (url != null && !url.trim().isEmpty()) {
                photos.add(url);
                metadata.add(detail(url, details, index));
            }
        }
        container.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < photos.size(); i++) {
            PostPhotoView photo = new PostPhotoView(container.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = Math.round(4 * container.getResources().getDisplayMetrics().density);
            container.addView(photo, params);
            Object binding = new Object();
            photo.binding = binding;
            final int index = i;
            photo.setOnClickListener(view -> openViewer(view.getContext(), photos, metadata, index));
            images.load(photo, photos.get(i), (width, height) -> {
                if (photo.binding == binding && photo.getParent() == container) photo.setSourceSize(width, height);
            });
        }
    }

    public static void openViewer(Context context, List<String> urls, List<ViewerPhoto> details, int index) {
        ArrayList<ViewerPhoto> aligned = new ArrayList<>();
        for (int item = 0; item < urls.size(); item++) aligned.add(detail(urls.get(item), details, item));
        Intent intent = new Intent(context, PhotoViewerActivity.class)
                .putStringArrayListExtra("photos", new ArrayList<>(urls)).putExtra("index", index)
                .putParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS, aligned);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static ViewerPhoto detail(String url, List<ViewerPhoto> details, int index) {
        ViewerPhoto photo = details != null && index < details.size() ? details.get(index) : null;
        return photo != null && photo.url.equals(url) ? photo : ViewerPhoto.fromUrl(url);
    }

    private static void clear(View view, Images images) {
        if (view instanceof PostPhotoView) {
            ((PostPhotoView) view).binding = null;
            ((PostPhotoView) view).loadAtMeasuredWidth(null);
            view.setOnClickListener(null);
        }
        if (view instanceof ImageView) images.clear((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clear(group.getChildAt(i), images);
        }
    }

    private static final class GlideImages implements Images {
        @Override public void load(PostPhotoView target, String url, SizeReady ready) {
            target.loadAtMeasuredWidth(width -> Glide.with(target).load(url)
                    // Bound decoding by this post's width and tallest displayed frame, not the source size.
                    .override(width, (int) Math.min(Integer.MAX_VALUE, (long) width * 2))
                    .fitCenter().dontAnimate().listener(new RequestListener<Drawable>() {
                        @Override public boolean onLoadFailed(@Nullable GlideException error, Object model,
                                Target<Drawable> drawableTarget, boolean first) { return false; }
                        @Override public boolean onResourceReady(Drawable drawable, Object model,
                                Target<Drawable> drawableTarget, DataSource source, boolean first) {
                            ready.onSize(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                            return false;
                        }
                    }).into(target));
        }

        @Override public void clear(ImageView target) {
            Glide.with(target.getContext().getApplicationContext()).clear(target);
            target.setImageDrawable(null);
        }
    }
}
