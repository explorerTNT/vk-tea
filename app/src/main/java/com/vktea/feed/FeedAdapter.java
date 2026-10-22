package com.vktea.feed;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        holder.text.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        holder.more.setVisibility(cut && !post.expanded ? View.VISIBLE : View.GONE);
        holder.more.setOnClickListener(v -> PostActivity.start(v.getContext(), post));

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

        applyLikeState(holder.likes, post);
        holder.likes.setText(post.likes > 0 ? formatCount(post.likes) : "");
        holder.comments.setText(post.comments > 0 ? formatCount(post.comments) : "");
        holder.reposts.setText(post.reposts > 0 ? formatCount(post.reposts) : "");
        holder.views.setText(post.views > 0 ? formatCount(post.views) : "");

        holder.likes.setOnClickListener(v -> toggleLike(post, holder.getAdapterPosition(), v));
        holder.comments.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        holder.reposts.setOnClickListener(v -> repost(post, v));
        holder.itemView.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        holder.header.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAuthorClick(post.sourceId);
            }
        });
    }

    private void applyLikeState(TextView view, Post post) {
        int color = ContextCompat.getColor(view.getContext(),
                post.userLikes ? R.color.vk_blue : R.color.post_icons);
        view.setTextColor(color);
        view.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private void toggleLike(Post post, int position, View view) {
        String type = post.likeType == Post.TYPE_PHOTO ? "photo" : "post";
        String method = post.userLikes ? "likes.delete" : "likes.add";
        post.userLikes = !post.userLikes;
        post.likes += post.userLikes ? 1 : -1;
        notifyItemChanged(position);
        VKApi.get(view.getContext(), method,
                "type=" + type + "&owner_id=" + post.likeOwnerId + "&item_id=" + post.likeItemId,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                    }

                    @Override
                    public void error(Exception e) {
                        post.userLikes = !post.userLikes;
                        post.likes += post.userLikes ? 1 : -1;
                        notifyItemChanged(position);
                    }
                });
    }

    private void repost(Post post, View itemView) {
        if (post.likeType == Post.TYPE_PHOTO) {
            Toast.makeText(itemView.getContext(), R.string.repost_photo_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        VKApi.get(itemView.getContext(), "wall.repost",
                "object=wall" + post.likeOwnerId + "_" + post.likeItemId,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        Toast.makeText(itemView.getContext(), R.string.repost_done, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void error(Exception e) {
                        Toast.makeText(itemView.getContext(), R.string.repost_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    public static String formatCount(int count) {
        if (count < 1000) {
            return String.valueOf(count);
        }
        if (count < 1000000) {
            float v = count / 1000f;
            return String.format(Locale.US, v < 100 ? "%.1fK" : "%.0fK", v);
        }
        return String.format(Locale.US, "%.1fM", count / 1000000f);
    }

    private void buildPhotoGrid(LinearLayout container, List<String> urls) {
        int n = urls.size();
        int gap = 1;
        android.content.Context ctx = container.getContext();
        if (n == 1) {
            container.addView(makePhoto(ctx, urls.get(0), ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        int index = 0;
        boolean firstRow = true;
        while (index < n) {
            int remaining = n - index;
            int perRow;
            if (firstRow && n > 3) {
                perRow = 1;
            } else if (remaining >= 3) {
                perRow = 3;
            } else {
                perRow = remaining;
            }
            if (perRow == 1) {
                container.addView(makePhoto(ctx, urls.get(index), ViewGroup.LayoutParams.MATCH_PARENT,
                        firstRow ? 0 : gap));
                index++;
            } else {
                container.addView(makeRow(ctx, urls.subList(index, index + perRow), gap, firstRow ? 0 : gap));
                index += perRow;
            }
            firstRow = false;
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
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int target = ctx.getResources().getDisplayMetrics().widthPixels;
        Glide.with(iv).load(url).override(target, com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(iv);
        return iv;
    }

    private ImageView makeCell(android.content.Context ctx, String url, int gap) {
        ImageView iv = new ImageView(ctx);
        int h = (int) (160 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(gap, gap, gap, gap);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int target = (int) (200 * ctx.getResources().getDisplayMetrics().density);
        Glide.with(iv).load(url).override(target, target).into(iv);
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
