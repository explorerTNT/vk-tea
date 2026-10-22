package com.vktea.menu;

import android.graphics.Color;
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

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<MenuEntry> entries;
    private final MenuEntry.Callback callback;
    private int selectedId;

    public LeftMenuAdapter(List<MenuEntry> entries, MenuEntry.Callback callback, int selectedId) {
        this.entries = entries;
        this.callback = callback;
        this.selectedId = selectedId;
    }

    public void setSelectedId(int id) {
        if (selectedId != id) {
            int old = selectedId;
            selectedId = id;
            notifyItemChanged(indexOf(old) + 1);
            notifyItemChanged(indexOf(id) + 1);
        }
    }

    private int indexOf(int id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_menu_header, parent, false));
        }
        return new ItemViewHolder(inflater.inflate(R.layout.item_menu_entry, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ItemViewHolder) {
            MenuEntry e = entries.get(position - 1);
            ((ItemViewHolder) holder).bind(e, e.id == selectedId);
        }
    }

    @Override
    public int getItemCount() {
        return entries.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_ITEM;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
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
                    MenuEntry e = entries.get(pos - 1);
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
                    : Color.TRANSPARENT);
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
