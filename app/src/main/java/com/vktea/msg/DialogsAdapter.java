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

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;

import java.util.ArrayList;
import java.util.List;

public class DialogsAdapter extends RecyclerView.Adapter<DialogsAdapter.DialogViewHolder> {

    private final List<Dialog> dialogs = new ArrayList<>();

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
            if (d.peerId == id && (d.title == null || d.title.isEmpty())) {
                d.title = name;
                d.photo = photo;
                notifyItemChanged(i);
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
        if (dialog.photo != null && !dialog.photo.isEmpty()) {
            Glide.with(holder.avatar).load(dialog.photo)
                    .apply(RequestOptions.circleCropTransform().override(120, 120))
                    .into(holder.avatar);
        }
        holder.itemView.setOnClickListener(v ->
                holder.itemView.getContext().startActivity(
                        new Intent(holder.itemView.getContext(), ChatActivity.class)
                                .putExtra(ChatActivity.EXTRA_PEER_ID, dialog.peerId)
                                .putExtra(ChatActivity.EXTRA_TITLE, dialog.title)));
    }

    @Override
    public int getItemCount() {
        return dialogs.size();
    }

    static class DialogViewHolder extends RecyclerView.ViewHolder {

        final ImageView avatar;
        final TextView name;
        final TextView excerpt;
        final TextView time;
        final View unread;

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
