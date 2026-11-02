package com.vktea.notifications;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.R;
import com.vktea.msg.MessageAvatars;
import com.vktea.util.RuDate;
import java.util.Collections;
import java.util.List;

final class NotificationsAdapter extends RecyclerView.Adapter<NotificationsAdapter.Holder> {
    interface Listener { void open(NotificationEvent event); }
    private final Listener listener;
    private List<NotificationEvent> items = Collections.emptyList();
    NotificationsAdapter(Listener listener) { this.listener = listener; setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY); }
    void submit(List<NotificationEvent> value) {
        if (items == value) return;
        items = value; notifyDataSetChanged();
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        NotificationEvent event = items.get(position);
        holder.actor.setText(event.actorName == null || event.actorName.isEmpty()
                ? holder.itemView.getContext().getString(R.string.notifications_unknown_actor) : event.actorName);
        holder.action.setText(actionLabel(event.type));
        holder.text.setText(event.text);
        holder.text.setVisibility(event.text == null || event.text.isEmpty() ? View.GONE : View.VISIBLE);
        holder.parent.setText(event.parentText);
        holder.parent.setVisibility(event.parentText == null || event.parentText.isEmpty() ? View.GONE : View.VISIBLE);
        holder.date.setText(event.date > 0 ? RuDate.format(event.date) : "");
        MessageAvatars.bind(holder.avatar, event.actorPhoto, false);
        boolean canOpen = event.url != null && !event.url.isEmpty();
        holder.itemView.setClickable(canOpen);
        holder.itemView.setFocusable(canOpen);
        holder.itemView.setOnClickListener(canOpen ? v -> listener.open(event) : null);
    }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        MessageAvatars.clear(holder.avatar); holder.itemView.setOnClickListener(null); super.onViewRecycled(holder);
    }
    @Override public int getItemCount() { return items.size(); }
    private static int actionLabel(String type) {
        if (type == null) return R.string.notifications_action_other;
        switch (type) {
            case "mention": case "mention_comments": case "mention_comment_photo": case "mention_comment_video":
                return R.string.notifications_action_mention;
            case "comment_post": case "comment_photo": case "comment_video": case "reply_topic":
                return R.string.notifications_action_comment;
            case "like_post": case "like_photo": case "like_video": case "like_comment":
                return R.string.notifications_action_like;
            case "copy_post": case "copy_photo": case "copy_video": return R.string.notifications_action_copy;
            case "follow": return R.string.notifications_action_follow;
            case "friend_accepted": return R.string.notifications_action_friend;
            case "wall": case "wall_publish": return R.string.notifications_action_wall;
            default: return R.string.notifications_action_other;
        }
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView actor, action, text, parent, date;
        Holder(View view) {
            super(view);
            avatar = view.findViewById(R.id.notification_avatar);
            actor = view.findViewById(R.id.notification_actor);
            action = view.findViewById(R.id.notification_action);
            text = view.findViewById(R.id.notification_text);
            parent = view.findViewById(R.id.notification_parent);
            date = view.findViewById(R.id.notification_date);
        }
    }
}
