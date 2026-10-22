package com.vktea.feed;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
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
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.util.VkTextUtils;

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
        int linkColor = ContextCompat.getColor(holder.text.getContext(), R.color.post_name);

        holder.name.setText(post.sourceName);
        holder.time.setText(com.vktea.util.RuDate.format(post.date));

        boolean cut = post.text.length() > 300;
        holder.text.setText(VkTextUtils.parse(cut && !post.expanded ? post.text : post.text,
                linkColor, url -> openUrl(holder.text, url)));
        holder.text.setMovementMethod(LinkMovementMethod.getInstance());
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

        bindRepost(holder, post, linkColor);
        bindVideo(holder, post);
        if (!post.cards.isEmpty()) {
            holder.cards.setVisibility(View.VISIBLE);
            CardsRenderer.render(holder.cards, post, linkColor);
        } else {
            holder.cards.setVisibility(View.GONE);
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

        holder.likes.setOnClickListener(v -> {
            toggleLike(post, holder.getAdapterPosition(), v);
            if (!post.userLikes) {
                animateLike(holder.likes);
            }
        });
        holder.comments.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        holder.reposts.setOnClickListener(v -> repost(post, v));
        holder.itemView.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        holder.options.setOnClickListener(v -> showPostMenu(v, post, holder.getAdapterPosition()));
        if (post.comments > 0) {
            holder.showComments.setVisibility(View.VISIBLE);
            holder.showComments.setText(String.format(
                    holder.showComments.getContext().getString(R.string.show_comments_fmt), post.comments));
            holder.showComments.setOnClickListener(v -> PostActivity.start(v.getContext(), post));
        } else {
            holder.showComments.setVisibility(View.GONE);
        }
        holder.header.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAuthorClick(post.sourceId);
            }
        });
    }

    private void animateLike(View anchor) {
        android.view.animation.ScaleAnimation sa = new android.view.animation.ScaleAnimation(
                1f, 1.4f, 1f, 1.4f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        sa.setDuration(160);
        sa.setRepeatCount(1);
        sa.setRepeatMode(android.view.animation.Animation.REVERSE);
        anchor.startAnimation(sa);
    }

    private void showPostMenu(View anchor, Post post, int position) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(anchor.getContext(), anchor);
        java.util.List<String> acts = new ArrayList<>();
        android.content.Context ctx = anchor.getContext();
        if (post.likeType == Post.TYPE_POST) {
            pm.getMenu().add(0, acts.size(), 0, R.string.menu_copy_link);
            acts.add("link");
            if (post.isRepost()) {
                pm.getMenu().add(0, acts.size(), 0, R.string.menu_show_original);
                acts.add("orig");
            }
            pm.getMenu().add(0, acts.size(), 0, R.string.menu_hide_from_feed);
            acts.add("hide");
            pm.getMenu().add(0, acts.size(), 0, R.string.menu_hide_not_interesting);
            acts.add("hide2");
            String selfId = com.vktea.auth.Session.getUserId(ctx);
            if (selfId != null && String.valueOf(post.likeOwnerId).equals(selfId)) {
                pm.getMenu().add(0, acts.size(), 0, R.string.menu_delete);
                acts.add("del");
            }
            pm.getMenu().add(0, acts.size(), 0, R.string.menu_report);
            acts.add("report");
        }
        pm.setOnMenuItemClickListener(item -> {
            performPostAction(ctx, acts.get(item.getItemId()), post, position);
            return true;
        });
        pm.show();
    }

    private void performPostAction(android.content.Context ctx, String act, Post post, int position) {
        String oid = String.valueOf(post.likeOwnerId);
        String iid = String.valueOf(post.likeItemId);
        switch (act) {
            case "link": {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("link",
                        "https://vk.com/wall" + oid + "_" + iid));
                Toast.makeText(ctx, R.string.link_copied, Toast.LENGTH_SHORT).show();
                break;
            }
            case "orig":
                ctx.startActivity(new Intent(ctx, AuthWebViewActivity.class)
                        .setData(Uri.parse("https://m.vk.com/wall" + post.repostOwnerId + "_" + post.repostPostId)));
                break;
            case "hide":
                apiAction(ctx, "newsfeed.addBan", "owner_ids=" + oid, R.string.hidden_from_feed);
                break;
            case "hide2":
                apiAction(ctx, "newsfeed.ignoreItem", "type=wall&owner_id=" + oid + "&item_id=" + iid,
                        R.string.hidden_not_interesting);
                break;
            case "del":
                apiAction(ctx, "wall.delete", "owner_id=" + oid + "&post_id=" + iid, R.string.post_deleted);
                break;
            case "report":
                apiAction(ctx, "wall.reportPost", "owner_id=" + oid + "&post_id=" + iid + "&reason=0", R.string.reported);
                break;
        }
    }

    private void apiAction(android.content.Context ctx, String method, String params, int okMsg) {
        VKApi.get(ctx, method, params, new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void error(Exception e) {
                Toast.makeText(ctx, R.string.action_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindRepost(PostViewHolder holder, Post post, int linkColor) {
        if (!post.isRepost()) {
            holder.repostBlock.setVisibility(View.GONE);
            return;
        }
        holder.repostBlock.setVisibility(View.VISIBLE);
        holder.repostName.setText(post.repostAuthorName);
        holder.repostTime.setText(com.vktea.util.RuDate.format(post.repostDate));
        holder.repostText.setText(VkTextUtils.parse(post.repostText, linkColor,
                url -> openUrl(holder.repostText, url)));
        holder.repostText.setMovementMethod(LinkMovementMethod.getInstance());
        if (post.repostAuthorPhoto != null && !post.repostAuthorPhoto.isEmpty()) {
            Glide.with(holder.repostAvatar).load(post.repostAuthorPhoto)
                    .apply(RequestOptions.circleCropTransform())
                    .into(holder.repostAvatar);
        }
        holder.repostPhotos.removeAllViews();
        if (!post.repostPhotos.isEmpty()) {
            buildPhotoGrid(holder.repostPhotos, post.repostPhotos);
        }
        holder.repostBlock.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), AuthWebViewActivity.class);
            intent.setData(Uri.parse("https://m.vk.com/wall" + post.repostOwnerId + "_" + post.repostPostId));
            v.getContext().startActivity(intent);
        });
    }

    private void bindVideo(PostViewHolder holder, Post post) {
        if (!post.hasVideo()) {
            holder.videoBlock.setVisibility(View.GONE);
            return;
        }
        holder.videoBlock.setVisibility(View.VISIBLE);
        Glide.with(holder.videoPreview).load(post.videoPreview).into(holder.videoPreview);
        holder.videoBlock.setOnClickListener(v ->
                VideoPlayerActivity.start(v.getContext(), post.videoOwner, post.videoId));
    }

    private void openUrl(View view, String url) {
        view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
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
            container.addView(makePhoto(ctx, urls.get(0), 0, urls));
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
                container.addView(makePhoto(ctx, urls.get(index), index, urls));
                index++;
            } else {
                container.addView(makeRow(ctx, urls.subList(index, index + perRow), index, urls, gap));
                index += perRow;
            }
            firstRow = false;
        }
    }

    private LinearLayout makeRow(android.content.Context ctx, List<String> urls, int offset, List<String> all, int gap) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < urls.size(); i++) {
            row.addView(makeCell(ctx, urls.get(i), offset + i, all, gap));
        }
        return row;
    }

    private ImageView makePhoto(android.content.Context ctx, String url, int index, List<String> all) {
        ImageView iv = new ImageView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(1, 1, 1, 1);
        iv.setLayoutParams(lp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int target = ctx.getResources().getDisplayMetrics().widthPixels;
        Glide.with(iv).load(url).override(target, com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(iv);
        iv.setOnClickListener(v -> openViewer(ctx, all, index));
        return iv;
    }

    private ImageView makeCell(android.content.Context ctx, String url, int index, List<String> all, int gap) {
        ImageView iv = new ImageView(ctx);
        int h = (int) (160 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(gap, gap, gap, gap);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int target = (int) (200 * ctx.getResources().getDisplayMetrics().density);
        Glide.with(iv).load(url).override(target, target).into(iv);
        iv.setOnClickListener(v -> openViewer(ctx, all, index));
        return iv;
    }

    private void openViewer(android.content.Context ctx, List<String> urls, int index) {
        ctx.startActivity(new Intent(ctx, PhotoViewerActivity.class)
                .putStringArrayListExtra("photos", new ArrayList<>(urls))
                .putExtra("index", index));
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
        final View repostBlock;
        final ImageView repostAvatar;
        final TextView repostName;
        final TextView repostTime;
        final TextView repostText;
        final LinearLayout repostPhotos;
        final View videoBlock;
        final ImageView videoPreview;
        final View options;
        final TextView showComments;
        final LinearLayout cards;

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
            repostBlock = itemView.findViewById(R.id.post_repost);
            repostAvatar = itemView.findViewById(R.id.repost_avatar);
            repostName = itemView.findViewById(R.id.repost_name);
            repostTime = itemView.findViewById(R.id.repost_time);
            repostText = itemView.findViewById(R.id.repost_text);
            repostPhotos = itemView.findViewById(R.id.repost_photos);
            videoBlock = itemView.findViewById(R.id.post_video);
            videoPreview = itemView.findViewById(R.id.video_preview);
            options = itemView.findViewById(R.id.post_options);
            showComments = itemView.findViewById(R.id.post_show_comments);
            cards = itemView.findViewById(R.id.post_cards);
        }
    }
}
