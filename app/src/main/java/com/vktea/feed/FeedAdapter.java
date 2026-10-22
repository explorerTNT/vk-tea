package com.vktea.feed;

import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

        boolean cut = post.text.length() > 300;
        holder.text.setText(post.text);
        holder.text.setMaxLines(cut && !post.expanded ? 7 : Integer.MAX_VALUE);
        holder.text.setOnClickListener(v -> {
            post.expanded = !post.expanded;
            notifyItemChanged(position);
        });

        if (cut && !post.expanded) {
            holder.more.setVisibility(View.VISIBLE);
        } else {
            holder.more.setVisibility(View.GONE);
        }

        holder.photoContainer.removeAllViews();
        if (post.photoUrls != null && !post.photoUrls.isEmpty()) {
            holder.photoContainer.setVisibility(View.VISIBLE);
            buildPhotoGrid(holder.photoContainer, post.photoUrls);
        } else {
            holder.photoContainer.setVisibility(View.GONE);
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

    private void buildPhotoGrid(LinearLayout container, List<String> urls) {
        int n = Math.min(urls.size(), 4);
        int gap = 1;
        android.content.Context ctx = container.getContext();
        if (n == 1) {
            container.addView(makePhoto(ctx, urls.get(0), ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        if (n == 3) {
            container.addView(makePhoto(ctx, urls.get(0), ViewGroup.LayoutParams.MATCH_PARENT, 0));
            container.addView(makeRow(ctx, urls.subList(1, 3), gap, gap));
            return;
        }
        container.addView(makeRow(ctx, urls.subList(0, Math.min(2, n)), gap, 0));
        if (n > 2) {
            container.addView(makeRow(ctx, urls.subList(2, n), gap, gap));
        }
    }

    private LinearLayout makeRow(android.content.Context ctx, List<String> urls, int gap, int topMargin) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, topMargin, 0, 0);
        for (String url : urls) {
            row.addView(makeCell(ctx, url, gap));
        }
        return row;
    }

    private ImageView makePhoto(android.content.Context ctx, String url, int width, int height) {
        ImageView iv = new ImageView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMargins(1, 1, 1, 1);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Glide.with(iv).load(url).override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(iv);
        return iv;
    }

    private ImageView makeCell(android.content.Context ctx, String url, int gap) {
        ImageView iv = new ImageView(ctx);
        int h = (int) (160 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(gap, gap, gap, gap);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Glide.with(iv).load(url).override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(iv);
        return iv;
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
        final LinearLayout photoContainer;
        final TextView more;
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
            photoContainer = itemView.findViewById(R.id.post_photos);
            more = itemView.findViewById(R.id.post_more);
            likes = itemView.findViewById(R.id.post_likes);
            comments = itemView.findViewById(R.id.post_comments);
            reposts = itemView.findViewById(R.id.post_reposts);
            views = itemView.findViewById(R.id.post_views);
            header = itemView.findViewById(R.id.post_header_btn);
        }
    }
}
