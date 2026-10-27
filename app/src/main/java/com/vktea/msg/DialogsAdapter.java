package com.vktea.msg;

import android.content.Intent;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DialogsAdapter extends RecyclerView.Adapter<DialogsAdapter.DialogViewHolder> {

    private final List<Dialog> dialogs = new ArrayList<>();
    private final MessageAvatars.Images images;

    public DialogsAdapter() { this(null); }

    DialogsAdapter(MessageAvatars.Images images) { this.images = images; }

    public List<Dialog> items() {
        return dialogs;
    }

    public void add(Dialog dialog) {
        dialogs.add(dialog);
        notifyItemInserted(dialogs.size() - 1);
    }

    public void clear() {
        int size = dialogs.size();
        dialogs.clear();
        notifyItemRangeRemoved(0, size);
    }

    public void applyUser(long id, String name, String photo) {
        for (int i = 0; i < dialogs.size(); i++) {
            Dialog d = dialogs.get(i);
            if (d.peerId == id) {
                String nextTitle = name != null && !name.trim().isEmpty() ? name.trim() : d.title;
                String nextPhoto = photo == null ? "" : photo.trim();
                if (!Objects.equals(d.title, nextTitle) || !Objects.equals(d.photo, nextPhoto)) {
                    d.title = nextTitle;
                    d.photo = nextPhoto;
                    notifyItemChanged(i);
                }
            }
        }
    }

    @NonNull
    @Override
    public DialogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DialogViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dialog, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull DialogViewHolder holder, int position) {
        Dialog dialog = dialogs.get(position);
        holder.name.setText(dialog.title);
        holder.excerpt.setText(dialog.excerpt);
        holder.time.setText(DateUtils.getRelativeTimeSpanString(dialog.date * 1000L,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        holder.unread.setVisibility(dialog.unread ? View.VISIBLE : View.GONE);
        holder.unread.setText(dialog.unreadCount > 0
                ? (dialog.unreadCount > 999 ? "999+" : String.valueOf(dialog.unreadCount)) : "");
        boolean group = dialog.peerId < 0 || dialog.peerId >= 2000000000L;
        if (images == null) MessageAvatars.bind(holder.avatar, dialog.photo, group);
        else MessageAvatars.bind(holder.avatar, dialog.photo, group, images);
        holder.itemView.setOnClickListener(v ->
                holder.itemView.getContext().startActivity(
                        new Intent(holder.itemView.getContext(), ChatActivity.class)
                                .putExtra(ChatActivity.EXTRA_PEER_ID, dialog.peerId)
                                .putExtra(ChatActivity.EXTRA_TITLE, dialog.title)
                                .putExtra(ChatActivity.EXTRA_PHOTO, dialog.photo)));
    }

    @Override
    public int getItemCount() {
        return dialogs.size();
    }

    @Override public void onViewRecycled(@NonNull DialogViewHolder holder) {
        if (images == null) MessageAvatars.clear(holder.avatar);
        else MessageAvatars.clear(holder.avatar, images);
        holder.itemView.setOnClickListener(null);
        super.onViewRecycled(holder);
    }

    static class DialogViewHolder extends RecyclerView.ViewHolder {

        final ImageView avatar;
        final TextView name;
        final TextView excerpt;
        final TextView time;
        final TextView unread;

        DialogViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.dialog_avatar);
            name = itemView.findViewById(R.id.dialog_name);
            excerpt = itemView.findViewById(R.id.dialog_excerpt);
            time = itemView.findViewById(R.id.dialog_time);
            unread = itemView.findViewById(R.id.dialog_unread);
        }
    }
}
