package com.vktea.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.audio.AudioUi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A filtered catalog view; playback selection is matched by stable VK identity. */
final class AudioQueueAdapter extends RecyclerView.Adapter<AudioQueueAdapter.Holder> {
    interface Callback {
        void onTrackClick(int libraryIndex);
        void onTrackLongClick(View anchor, int libraryIndex);
    }

    private static final class Track {
        final MediaItem item;
        final int libraryIndex;

        Track(MediaItem item, int libraryIndex) {
            this.item = item;
            this.libraryIndex = libraryIndex;
        }
    }

    private final Context context;
    private final Callback callback;
    private final List<Track> visible = new ArrayList<>();
    private final List<MediaItem> library = new ArrayList<>();
    private String query = "";
    private String currentMediaId;

    AudioQueueAdapter(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    void submit(List<MediaItem> items, String selectedMediaId) {
        library.clear();
        library.addAll(items);
        rebuild(selectedMediaId);
    }

    void setQuery(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!this.query.equals(normalized)) {
            this.query = normalized;
            rebuild(currentMediaId);
        }
    }

    int libraryIndexAt(int visiblePosition) {
        return visible.get(visiblePosition).libraryIndex;
    }

    private void rebuild(String selectedMediaId) {
        List<Track> previous = new ArrayList<>(visible);
        String previousMediaId = currentMediaId;
        List<Track> filtered = new ArrayList<>();
        for (int i = 0; i < library.size(); i++) {
            MediaItem item = library.get(i);
            String searchable = (AudioUi.title(context, item) + "\n" + AudioUi.artist(context, item))
                    .toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) {
                filtered.add(new Track(item, i));
            }
        }
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return filtered.size(); }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).item.mediaId.equals(filtered.get(newPosition).item.mediaId);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Track oldTrack = previous.get(oldPosition);
                Track newTrack = filtered.get(newPosition);
                return oldTrack.item.equals(newTrack.item)
                        && oldTrack.libraryIndex == newTrack.libraryIndex
                        && (oldTrack.item.mediaId.equals(previousMediaId)
                        == newTrack.item.mediaId.equals(selectedMediaId));
            }
        });
        visible.clear();
        visible.addAll(filtered);
        currentMediaId = selectedMediaId;
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
        Track track = visible.get(position);
        MediaItem item = track.item;
        holder.title.setText(AudioUi.title(context, item));
        holder.artist.setText(AudioUi.artist(context, item));
        holder.artist.setVisibility(holder.artist.length() == 0 ? View.GONE : View.VISIBLE);
        boolean selected = item.mediaId.equals(currentMediaId);
        holder.itemView.setSelected(selected);
        holder.current.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(Boolean.FALSE.equals(item.mediaMetadata.isPlayable) ? 0.55f : 1f);
        boolean local = AudioUi.isLocal(item);
        holder.local.setVisibility(local ? View.VISIBLE : View.GONE);
        long duration = AudioUi.duration(item);
        holder.duration.setText(duration > 0 ? AudioUi.formatTime(duration) : "");
        holder.duration.setVisibility(duration > 0 ? View.VISIBLE : View.GONE);
    }

    @Override public int getItemCount() { return visible.size(); }

    final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView artist;
        final TextView duration;
        final ImageView current;
        final ImageView local;

        Holder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.audio_track_title);
            artist = itemView.findViewById(R.id.audio_track_artist);
            duration = itemView.findViewById(R.id.audio_list_duration);
            current = itemView.findViewById(R.id.audio_list_current);
            local = itemView.findViewById(R.id.audio_list_local);
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    callback.onTrackClick(libraryIndexAt(position));
                }
            });
            itemView.setOnLongClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return false;
                }
                callback.onTrackLongClick(view, libraryIndexAt(position));
                return true;
            });
        }
    }
}
