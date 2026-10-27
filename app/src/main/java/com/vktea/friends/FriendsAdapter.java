package com.vktea.friends;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.msg.MessageAvatars;
import com.vktea.util.RuDate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FriendsAdapter extends RecyclerView.Adapter<FriendsAdapter.Holder> {
    public interface Listener {
        void openProfile(Friend friend);
        void openChat(Friend friend);
    }
    private final Listener listener;
    private List<Friend> items = Collections.emptyList();

    public FriendsAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void submit(List<Friend> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Friend friend = items.get(position);
        holder.name.setText(friend.name);
        MessageAvatars.bind(holder.avatar, friend.photo, false);
        holder.online.setVisibility(friend.online ? View.VISIBLE : View.GONE);
        holder.status.setTextColor(com.vktea.ui.TeaColors.get(holder.status.getContext(),
                friend.online ? R.color.vk_blue : R.color.post_time));
        if (friend.online) holder.status.setText(friend.onlineMobile ? R.string.friends_online_mobile : R.string.friends_online_status);
        else if (friend.lastSeen > 0) holder.status.setText(holder.status.getContext().getString(R.string.friends_last_seen,
                RuDate.format(Math.min(friend.lastSeen, Long.MAX_VALUE / 1000))));
        else holder.status.setText(friend.status.isEmpty() ? holder.status.getContext().getString(R.string.friends_offline_status) : friend.status);
        holder.message.setContentDescription(holder.message.getContext().getString(R.string.friends_write, friend.name));
        holder.itemView.setOnClickListener(view -> listener.openProfile(friend));
        holder.message.setOnClickListener(view -> listener.openChat(friend));
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        MessageAvatars.clear(holder.avatar);
        holder.itemView.setOnClickListener(null);
        holder.message.setOnClickListener(null);
        super.onViewRecycled(holder);
    }

    @Override public long getItemId(int position) { return items.get(position).id; }
    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name, status;
        final View online, message;
        Holder(View view) {
            super(view);
            avatar = view.findViewById(R.id.friend_avatar);
            name = view.findViewById(R.id.friend_name);
            status = view.findViewById(R.id.friend_status);
            online = view.findViewById(R.id.friend_online);
            message = view.findViewById(R.id.friend_message);
        }
    }
}
