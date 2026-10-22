package com.vktea.ui;

import android.view.LayoutInflater;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

import java.util.ArrayList;
import java.util.List;

/** Displays the service's queue; it never owns a second copy of the player. */
final class AudioQueueAdapter extends RecyclerView.Adapter<AudioQueueAdapter.Holder> {
    interface Callback {
        void onTrackClick(int index);
    }

    private final Callback callback;
    private final List<MediaItem> items = new ArrayList<>();
    private int currentIndex = -1;

    AudioQueueAdapter(Callback callback) {
        this.callback = callback;
    }

    void submit(List<MediaItem> queue, int index) {
        List<MediaItem> previous = new ArrayList<>(items);
        int previousIndex = currentIndex;
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return previous.size();
            }

            @Override
            public int getNewListSize() {
                return queue.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).mediaId.equals(queue.get(newPosition).mediaId);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).equals(queue.get(newPosition))
                        && (oldPosition == previousIndex) == (newPosition == index);
            }
        });
        items.clear();
        items.addAll(queue);
        currentIndex = index;
        changes.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        MediaItem item = items.get(position);
        holder.title.setText(item.mediaMetadata.title != null
                ? item.mediaMetadata.title : holder.itemView.getContext().getString(R.string.audio_untitled));
        holder.artist.setText(item.mediaMetadata.artist);
        holder.artist.setVisibility(item.mediaMetadata.artist == null ? View.GONE : View.VISIBLE);
        boolean selected = position == currentIndex;
        holder.itemView.setSelected(selected);
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                R.color.vk_blue));
        if (!selected) {
            holder.title.setTextColor(holder.normalTitleColor);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView artist;
        final ColorStateList normalTitleColor;

        Holder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.audio_track_title);
            normalTitleColor = title.getTextColors();
            artist = itemView.findViewById(R.id.audio_track_artist);
            itemView.setOnClickListener(view -> {
                int index = getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION) {
                    callback.onTrackClick(index);
                }
            });
        }
    }
}
