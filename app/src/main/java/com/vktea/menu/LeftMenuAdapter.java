package com.vktea.menu;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

import java.util.List;

public class LeftMenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_STATUS_SPACER = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_ITEM = 2;
    private static final int TYPE_FOOTER_SPACER = 3;

    private final List<MenuEntry> entries;
    private final MenuEntry.Callback callback;
    private int selectedId;
    private int topInset;
    private int bottomInset;
    private com.vktea.api.User user;

    public LeftMenuAdapter(List<MenuEntry> entries, MenuEntry.Callback callback, int selectedId) {
        this.entries = entries;
        this.callback = callback;
        this.selectedId = selectedId;
    }

    public void setHeaderUser(com.vktea.api.User user) {
        this.user = user;
        notifyItemChanged(1);
        if (user != null && user.counters != null) {
            applyCounter(R.id.menu_friends, user.counters.friends);
            applyCounter(R.id.menu_messages, user.counters.messages);
            applyCounter(R.id.menu_feedback, user.counters.notifications);
            applyCounter(R.id.menu_groups, user.counters.groups);
        }
    }

    private void applyCounter(int menuId, int value) {
        for (int i = 0; i < entries.size(); i++) {
            MenuEntry e = entries.get(i);
            if (e.id == menuId && e.counter != value) {
                e.counter = value;
                notifyItemChanged(i + 2);
            }
        }
    }

    public void setSystemInsets(int top, int bottom) {
        if (topInset != top || bottomInset != bottom) {
            topInset = top;
            bottomInset = bottom;
            notifyItemChanged(0);
            notifyItemChanged(getItemCount() - 1);
        }
    }

    public void setSelectedId(int id) {
        if (selectedId != id) {
            int old = selectedId;
            selectedId = id;
            int p = entryPosition(old);
            if (p >= 0) {
                notifyItemChanged(p);
            }
            p = entryPosition(id);
            if (p >= 0) {
                notifyItemChanged(p);
            }
        }
    }

    private int entryPosition(int id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                return i + 2;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_STATUS_SPACER || viewType == TYPE_FOOTER_SPACER) {
            View v = new View(parent.getContext());
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            return new SpacerViewHolder(v, viewType);
        }
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_menu_header, parent, false));
        }
        return new ItemViewHolder(inflater.inflate(R.layout.item_menu_entry, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_STATUS_SPACER) {
            holder.itemView.getLayoutParams().height = topInset;
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.vk_blue));
        } else if (type == TYPE_FOOTER_SPACER) {
            holder.itemView.getLayoutParams().height = bottomInset;
        } else if (type == TYPE_ITEM) {
            MenuEntry e = entries.get(position - 2);
            ((ItemViewHolder) holder).bind(e, e.id == selectedId);
        } else if (type == TYPE_HEADER) {
            ((HeaderViewHolder) holder).bind(user);
        }
    }

    @Override
    public int getItemCount() {
        return entries.size() + 3;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return TYPE_STATUS_SPACER;
        }
        if (position == getItemCount() - 1) {
            return TYPE_FOOTER_SPACER;
        }
        return position == 1 ? TYPE_HEADER : TYPE_ITEM;
    }

    static class SpacerViewHolder extends RecyclerView.ViewHolder {

        SpacerViewHolder(@NonNull View itemView, int type) {
            super(itemView);
            itemView.setTag(type);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final android.widget.TextView name;
        private final android.widget.TextView status;
        private final android.widget.ImageView avatar;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.menu_header_name);
            status = itemView.findViewById(R.id.menu_header_status);
            avatar = itemView.findViewById(R.id.menu_header_avatar);
        }

        void bind(com.vktea.api.User user) {
            if (user == null) {
                return;
            }
            name.setText(user.fullName());
            if (user.status != null && !user.status.isEmpty()) {
                status.setText(user.status);
            }
            if (user.photo200 != null && !user.photo200.isEmpty()) {
                com.bumptech.glide.Glide.with(itemView)
                        .load(user.photo200)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .into(avatar);
            }
        }
    }

    class ItemViewHolder extends RecyclerView.ViewHolder {

        private final ImageView icon;
        private final TextView title;
        private final TextView counter;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.menu_icon);
            title = itemView.findViewById(R.id.menu_title);
            counter = itemView.findViewById(R.id.menu_counter);
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && callback != null) {
                    MenuEntry e = entries.get(pos - 2);
                    setSelectedId(e.id);
                    callback.onMenuClick(e);
                }
            });
        }

        void bind(MenuEntry entry, boolean selected) {
            icon.setImageResource(entry.iconRes);
            title.setText(entry.titleRes);
            itemView.setBackgroundColor(selected
                    ? ContextCompat.getColor(itemView.getContext(), R.color.menu_selected_bg)
                    : ContextCompat.getColor(itemView.getContext(), R.color.menu_bg));
            int iconColor = selected
                    ? ContextCompat.getColor(icon.getContext(), R.color.vk_blue)
                    : ContextCompat.getColor(icon.getContext(), R.color.menu_icon_normal);
            icon.setColorFilter(iconColor);
            title.setTextColor(ContextCompat.getColor(title.getContext(),
                    selected ? R.color.vk_blue : R.color.almost_black));
            if (entry.counter > 0) {
                counter.setVisibility(View.VISIBLE);
                counter.setText(String.valueOf(entry.counter));
            } else {
                counter.setVisibility(View.GONE);
            }
        }
    }
}
