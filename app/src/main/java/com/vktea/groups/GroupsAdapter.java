package com.vktea.groups;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.msg.MessageAvatars;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Community rows replace every recycled field and retain the API's ordering. */
public final class GroupsAdapter extends RecyclerView.Adapter<GroupsAdapter.Holder> {
    public interface Listener { void openGroup(Group group); }
    private final Listener listener;
    private List<Group> items = Collections.emptyList();

    public GroupsAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void submit(List<Group> rows) {
        List<Group> previous = items;
        List<Group> next = Collections.unmodifiableList(new ArrayList<>(rows));
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldIndex, int newIndex) {
                return previous.get(oldIndex).id == next.get(newIndex).id;
            }
            @Override public boolean areContentsTheSame(int oldIndex, int newIndex) {
                Group old = previous.get(oldIndex), value = next.get(newIndex);
                return Objects.equals(old.name, value.name) && Objects.equals(old.photo, value.photo)
                        && Objects.equals(old.activity, value.activity) && Objects.equals(old.type, value.type)
                        && Objects.equals(old.screenName, value.screenName) && old.isClosed == value.isClosed
                        && old.isAdmin == value.isAdmin && old.adminLevel == value.adminLevel
                        && old.verified == value.verified && old.membersCount == value.membersCount;
            }
        });
        items = next;
        changes.dispatchUpdatesTo(this);
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Group group = items.get(position);
        Context context = holder.itemView.getContext();
        holder.name.setText(group.name);
        MessageAvatars.bind(holder.avatar, group.photo, true);
        holder.subtitle.setText(group.activity.isEmpty() ? context.getString(typeLabel(group.type)) : group.activity);
        holder.members.setVisibility(group.membersCount >= 0 ? View.VISIBLE : View.GONE);
        holder.members.setText(group.membersCount >= 0
                ? context.getString(R.string.groups_members_count, NumberFormat.getIntegerInstance().format(group.membersCount)) : "");
        holder.itemView.setOnClickListener(view -> listener.openGroup(group));
    }

    private static int typeLabel(String type) {
        switch (type) {
            case "group": return R.string.groups_type_group;
            case "page": return R.string.groups_type_page;
            case "event": return R.string.groups_type_event;
            default: return R.string.groups_type_unknown;
        }
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        MessageAvatars.clear(holder.avatar);
        holder.itemView.setOnClickListener(null);
        super.onViewRecycled(holder);
    }

    @Override public long getItemId(int position) { return items.get(position).id; }
    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name, subtitle, members;
        Holder(View view) {
            super(view);
            avatar = view.findViewById(R.id.group_avatar);
            name = view.findViewById(R.id.group_name);
            subtitle = view.findViewById(R.id.group_subtitle);
            members = view.findViewById(R.id.group_members);
        }
    }
}
