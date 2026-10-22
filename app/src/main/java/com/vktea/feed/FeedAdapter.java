package com.vktea.feed;

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

public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.PostViewHolder> {

    public interface Listener {
        void onAuthorClick(long sourceId);
    }

    private final List<Post> posts = new ArrayList<>();
    private final Listener listener;

    public FeedAdapter(Listener listener) {
        this.listener = listener;
    }

    public void addAll(List<Post> newPosts) {
        int start = posts.size();
        posts.addAll(newPosts);
        notifyItemRangeInserted(start, newPosts.size());
    }

    public void clear() {
        int size = posts.size();
        posts.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PostViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_post, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        Post post = posts.get(position);

        holder.name.setText(post.sourceName);
        holder.time.setText(com.vktea.util.RuDate.format(post.date));

        if (post.text == null || post.text.isEmpty()) {
            holder.text.setVisibility(View.GONE);
        } else {
            holder.text.setVisibility(View.VISIBLE);
            holder.text.setText(post.text);
        }

        if (post.photoUrl != null) {
            holder.photo.setVisibility(View.VISIBLE);
            android.util.Log.d("VKTea", "photo url: " + post.photoUrl);
            Glide.with(holder.photo)
                    .load(post.photoUrl)
                    .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                    .into(holder.photo);
        } else {
            holder.photo.setVisibility(View.GONE);
        }

        if (post.sourcePhoto != null) {
            Glide.with(holder.avatar).load(post.sourcePhoto)
                    .apply(RequestOptions.circleCropTransform())
                    .into(holder.avatar);
        }

        holder.likes.setText(post.likes > 0 ? String.valueOf(post.likes) : "");
        holder.comments.setText(post.comments > 0 ? String.valueOf(post.comments) : "");
        holder.reposts.setText(post.reposts > 0 ? String.valueOf(post.reposts) : "");
        holder.views.setText(post.views > 0 ? String.valueOf(post.views) : "");

        holder.header.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAuthorClick(post.sourceId);
            }
        });
    }

    @Override
    public int getItemCount() {
        return posts.size();
    }

    static class PostViewHolder extends RecyclerView.ViewHolder {

        final ImageView avatar;
        final TextView name;
        final TextView time;
        final TextView text;
        final ImageView photo;
        final TextView likes;
        final TextView comments;
        final TextView reposts;
        final TextView views;
        final View header;

        PostViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.post_avatar);
            name = itemView.findViewById(R.id.post_name);
            time = itemView.findViewById(R.id.post_time);
            text = itemView.findViewById(R.id.post_text);
            photo = itemView.findViewById(R.id.post_photo);
            likes = itemView.findViewById(R.id.post_likes);
            comments = itemView.findViewById(R.id.post_comments);
            reposts = itemView.findViewById(R.id.post_reposts);
            views = itemView.findViewById(R.id.post_views);
            header = itemView.findViewById(R.id.post_header_btn);
        }
    }
}
