package com.vktea.menu;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

import java.util.List;

public class LeftMenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<MenuEntry> entries;
    private final MenuEntry.Callback callback;

    public LeftMenuAdapter(List<MenuEntry> entries, MenuEntry.Callback callback) {
        this.entries = entries;
        this.callback = callback;
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
            ((ItemViewHolder) holder).bind(entries.get(position - 1));
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

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.menu_icon);
            title = itemView.findViewById(R.id.menu_title);
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && callback != null) {
                    callback.onMenuClick(entries.get(pos - 1));
                }
            });
        }

        void bind(MenuEntry entry) {
            icon.setImageResource(entry.iconRes);
            title.setText(entry.titleRes);
        }
    }
}
