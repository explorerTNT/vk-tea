package com.vktea.photos;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.vktea.R;
import com.vktea.feed.ViewerPhoto;

import java.util.ArrayList;
import java.util.List;

/** Grid cells and album rows for the photos section. */
final class PhotosAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void openPhoto(int index);
        void openAlbum(PhotosModels.AlbumEntry album);
    }

    private static final int TYPE_PHOTO = 0;
    private static final int TYPE_ALBUM = 1;

    private final List<PhotosModels.PhotoEntry> photos = new ArrayList<>();
    private final List<PhotosModels.AlbumEntry> albums = new ArrayList<>();
    private boolean albumMode;
    private final Listener listener;

    PhotosAdapter(Listener listener) {
        this.listener = listener;
    }

    void showPhotos(List<PhotosModels.PhotoEntry> rows, boolean append) {
        albumMode = false;
        if (!append) photos.clear();
        photos.addAll(rows);
        notifyDataSetChanged();
    }

    void showAlbums(List<PhotosModels.AlbumEntry> rows) {
        albumMode = true;
        albums.clear();
        albums.addAll(rows);
        notifyDataSetChanged();
    }

    List<PhotosModels.PhotoEntry> currentPhotos() { return new ArrayList<>(photos); }

    int photoCount() { return photos.size(); }

    boolean isAlbumMode() { return albumMode; }

    @Override public int getItemCount() {
        return albumMode ? albums.size() : photos.size();
    }

    @Override public int getItemViewType(int position) {
        return albumMode ? TYPE_ALBUM : TYPE_PHOTO;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ALBUM) {
            return new AlbumHolder(inflater.inflate(R.layout.item_photo_album, parent, false));
        }
        return new PhotoHolder(inflater.inflate(R.layout.item_photo_cell, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof AlbumHolder) {
            PhotosModels.AlbumEntry album = albums.get(position);
            AlbumHolder h = (AlbumHolder) holder;
            h.title.setText(album.title);
            h.size.setText(h.itemView.getContext().getString(R.string.photos_album_count, album.size));
            Glide.with(h.cover).load(album.coverUrl.isEmpty() ? null : album.coverUrl)
                    .centerCrop().into(h.cover);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.openAlbum(album);
            });
            return;
        }
        PhotoHolder h = (PhotoHolder) holder;
        PhotosModels.PhotoEntry photo = photos.get(position);
        Glide.with(h.image).load(photo.url).centerCrop().into(h.image);
        h.image.setOnClickListener(v -> {
            if (listener != null) listener.openPhoto(holder.getAdapterPosition());
        });
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PhotoHolder) {
            Glide.with(((PhotoHolder) holder).image).clear(((PhotoHolder) holder).image);
        }
        super.onViewRecycled(holder);
    }

    ViewerPhoto metaAt(int index) {
        return index >= 0 && index < photos.size() ? photos.get(index).meta : null;
    }

    static final class PhotoHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        PhotoHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.photo_cell_image);
        }
    }

    static final class AlbumHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, size;
        AlbumHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.album_cover);
            title = itemView.findViewById(R.id.album_title);
            size = itemView.findViewById(R.id.album_size);
        }
    }
}
