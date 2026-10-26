package com.vktea.feed;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.PhotoView;
import com.vktea.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Display-only pager: exports use the original URL independently of these bounded previews. */
public final class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.Holder> {
    public enum LoadState { LOADING, READY, ERROR }
    public interface Callback {
        void onToggleChrome();
        void onZoomChanged(int position, boolean zoomed);
        default void onLoadStateChanged(int position, LoadState state) { }
    }
    interface Result { void ready(); void failed(); }
    interface Images {
        void load(PhotoView target, String url, int width, int height, Result result);
        void clear(PhotoView target);
    }

    private final List<String> photos;
    private final Callback callback;
    private final Images images;
    private final Set<Holder> bound = new HashSet<>();

    public PhotoPagerAdapter(List<String> photos, Callback callback) {
        this(photos, callback, new GlideImages());
    }

    PhotoPagerAdapter(List<String> photos, Callback callback, Images images) {
        this.photos = new ArrayList<>(photos);
        this.callback = callback;
        this.images = images;
    }

    @Override public int getItemCount() { return photos.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder holder = new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo_viewer, parent, false));
        holder.photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        holder.photo.setMaximumScale(4f);
        holder.photo.setMediumScale(2f);
        holder.photo.setOnViewTapListener((view, x, y) -> {
            if (holder.position >= 0 && callback != null) callback.onToggleChrome();
        });
        holder.photo.setOnMatrixChangeListener(rect -> reportZoom(holder));
        holder.photo.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            reportZoom(holder);
            request(holder);
        });
        holder.retry.setOnClickListener(view -> retryCurrent(holder.position));
        return holder;
    }

    // The copied intent list is immutable for this adapter's lifetime: its indexes never move.
    @SuppressLint("RecyclerView")
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        cancel(holder);
        holder.position = position;
        holder.detached = false;
        holder.state = null;
        bound.add(holder);
        holder.photo.setContentDescription(holder.photo.getContext().getString(
                R.string.photo_viewer_photo_number, position + 1, photos.size()));
        state(holder, LoadState.LOADING);
        request(holder);
    }

    private void request(Holder holder) {
        if (holder.position < 0 || holder.detached) return;
        int width = bounded(holder.photo.getWidth()), height = bounded(holder.photo.getHeight());
        // Chrome visibility changes the viewport: retain the loaded photo instead of downloading it again.
        if (width <= 0 || height <= 0 || (holder.width > 0 && holder.height > 0)) return;
        cancel(holder);
        holder.width = width;
        holder.height = height;
        state(holder, LoadState.LOADING);
        long generation = holder.generation;
        images.load(holder.photo, photos.get(holder.position), width, height, new Result() {
            private boolean current() { return holder.generation == generation && holder.position >= 0 && !holder.detached; }
            @Override public void ready() { if (current()) state(holder, LoadState.READY); }
            @Override public void failed() { if (current()) state(holder, LoadState.ERROR); }
        });
    }

    private static int bounded(int viewport) { return (int) Math.min(4096L, viewport * 2L); }

    public void retryCurrent(int position) {
        for (Holder holder : new ArrayList<>(bound)) if (holder.position == position) {
            holder.width = holder.height = 0;
            request(holder);
        }
    }

    public void resetZoom(int position) {
        for (Holder holder : bound) if (holder.position == position) {
            holder.photo.setScale(holder.photo.getMinimumScale(), false);
            reportZoom(holder);
        }
    }

    private void reportZoom(Holder holder) {
        if (holder.photo instanceof ViewerPhotoView && ((ViewerPhotoView) holder.photo).isPreservingZoom()) return;
        boolean zoomed = holder.photo.getScale() > holder.photo.getMinimumScale() + .01f;
        if (holder.position >= 0 && holder.zoomed != zoomed) {
            holder.zoomed = zoomed;
            if (callback != null) callback.onZoomChanged(holder.position, zoomed);
        }
    }

    private void state(Holder holder, LoadState value) {
        holder.loading.setVisibility(value == LoadState.LOADING ? View.VISIBLE : View.GONE);
        holder.error.setVisibility(value == LoadState.ERROR ? View.VISIBLE : View.GONE);
        if (holder.state != value) {
            holder.state = value;
            if (callback != null && holder.position >= 0) callback.onLoadStateChanged(holder.position, value);
        }
    }

    private void cancel(Holder holder) {
        holder.generation++;
        holder.width = holder.height = 0;
        images.clear(holder.photo);
        holder.photo.setScale(holder.photo.getMinimumScale(), false);
        reportZoom(holder);
    }

    @Override public void onViewDetachedFromWindow(@NonNull Holder holder) {
        holder.detached = true;
        cancel(holder);
        super.onViewDetachedFromWindow(holder);
    }

    @Override public void onViewAttachedToWindow(@NonNull Holder holder) {
        super.onViewAttachedToWindow(holder);
        holder.detached = false;
        request(holder);
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        cancel(holder);
        bound.remove(holder);
        holder.position = -1;
        holder.state = null;
        super.onViewRecycled(holder);
    }

    public void release() {
        for (Holder holder : new ArrayList<>(bound)) onViewRecycled(holder);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final PhotoView photo;
        final View loading, error, retry;
        int position = -1, width, height;
        long generation;
        boolean zoomed, detached;
        LoadState state;
        Holder(View view) {
            super(view);
            photo = view.findViewById(R.id.viewer_photo);
            loading = view.findViewById(R.id.viewer_loading);
            error = view.findViewById(R.id.viewer_error_panel);
            retry = view.findViewById(R.id.viewer_retry);
        }
    }

    private static final class GlideImages implements Images {
        @Override public void load(PhotoView target, String url, int width, int height, Result result) {
            Glide.with(target).load(url).override(width, height).fitCenter().dontAnimate()
                    .listener(new RequestListener<Drawable>() {
                        @Override public boolean onLoadFailed(@Nullable GlideException error, Object model,
                                Target<Drawable> drawable, boolean first) { result.failed(); return false; }
                        @Override public boolean onResourceReady(Drawable drawable, Object model,
                                Target<Drawable> target, DataSource source, boolean first) { result.ready(); return false; }
                    }).into(target);
        }
        @Override public void clear(PhotoView target) {
            Glide.with(target.getContext().getApplicationContext()).clear(target);
            target.setImageDrawable(null);
        }
    }
}
