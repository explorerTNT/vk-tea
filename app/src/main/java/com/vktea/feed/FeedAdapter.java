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
import androidx.recyclerview.widget.LinearLayoutManager;
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
    private final RecyclerView.OnScrollListener collapseScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
            updateStickyCollapse(list);
        }
    };
    private final View.OnLayoutChangeListener collapseLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updateStickyCollapse((RecyclerView) view);

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

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView list) {
        super.onAttachedToRecyclerView(list);
        list.addOnScrollListener(collapseScrollListener);
        list.addOnLayoutChangeListener(collapseLayoutListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView list) {
        list.removeOnScrollListener(collapseScrollListener);
        list.removeOnLayoutChangeListener(collapseLayoutListener);
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (holder instanceof PostViewHolder) ((PostViewHolder) holder).collapseTop.setTranslationY(0);
        }
        super.onDetachedFromRecyclerView(list);
    }

    private void updateStickyCollapse(RecyclerView list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (holder instanceof PostViewHolder && holder.getBindingAdapter() == this) {
                updateStickyCollapse((PostViewHolder) holder);
            }
        }
    }

    private static void updateStickyCollapse(PostViewHolder holder) {
        View button = holder.collapseTop;
        if (holder.boundPost == null || !holder.boundPost.expanded
                || button.getVisibility() != View.VISIBLE
                || !(holder.itemView.getParent() instanceof RecyclerView)) {
            button.setTranslationY(0);
            return;
        }
        RecyclerView list = (RecyclerView) holder.itemView.getParent();
        float desired = list.getPaddingTop() - holder.itemView.getY() - button.getTop();
        // Keep the real, clickable view above the photos, but inside its own post content.
        float limit = Math.max(0, holder.content.getBottom() - button.getBottom());
        button.setTranslationY(Math.max(0, Math.min(desired, limit)));
    }

    @Override
    public void onViewAttachedToWindow(@NonNull PostViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        updateStickyCollapse(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PostViewHolder holder) {
        holder.collapseTop.setTranslationY(0);
        super.onViewDetachedFromWindow(holder);
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
        holder.boundPost = null;
        holder.content.reset();
        holder.more.setVisibility(View.GONE);
        holder.more.setOnClickListener(null);
        holder.collapseTop.setVisibility(View.GONE);
        holder.collapseTop.setTranslationY(0);
        holder.collapseTop.setOnClickListener(null);
        holder.repostBlock.setOnClickListener(null);
        holder.videoBlock.setOnClickListener(null);
        holder.showComments.setOnClickListener(null);
        holder.boundPost = post;
        holder.collapseTop.setVisibility(post.expanded ? View.VISIBLE : View.GONE);
        holder.more.setText(post.expanded ? R.string.feed_collapse : R.string.feed_show_full);
        holder.content.bindForFeed(post.expanded, overflowing -> {
            if (holder.boundPost == post) holder.more.setVisibility(overflowing ? View.VISIBLE : View.GONE);
        });
        View.OnClickListener toggleContent = view -> {
            if (holder.boundPost != post) return;
            if (view == holder.collapseTop && !post.expanded) return;
            post.expanded = !post.expanded;
            holder.content.setExpanded(post.expanded);
            holder.more.setText(post.expanded ? R.string.feed_collapse : R.string.feed_show_full);
            holder.collapseTop.setVisibility(post.expanded ? View.VISIBLE : View.GONE);
            updateStickyCollapse(holder);
            if (!post.expanded) anchorCollapsedPost(holder);
        };
        holder.more.setOnClickListener(toggleContent);
        holder.collapseTop.setOnClickListener(toggleContent);
        updateStickyCollapse(holder);
        int linkColor = ContextCompat.getColor(holder.text.getContext(), R.color.post_name);

        holder.name.setText(post.sourceName);
        holder.time.setText(com.vktea.util.RuDate.format(post.date));

        holder.text.setText(VkTextUtils.parse(post.text,
                linkColor, url -> openUrl(holder.text, url)));
        holder.text.setMovementMethod(LinkMovementMethod.getInstance());
        holder.text.setMaxLines(Integer.MAX_VALUE);
        holder.text.setOnClickListener(v -> PostActivity.start(v.getContext(), post));

        PostPhotosRenderer.render(holder.photoContainer, post.photoUrls, post.photoDetails);

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
        holder.reposts.setEnabled(PostSharing.attachment(post).hasIdentity());
        holder.reposts.setOnClickListener(v -> PostSharing.open(v.getContext(), post));
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

    private void anchorCollapsedPost(PostViewHolder holder) {
        if (!(holder.itemView.getParent() instanceof RecyclerView)) return;
        RecyclerView list = (RecyclerView) holder.itemView.getParent();
        int position = holder.getAbsoluteAdapterPosition();
        if (position == RecyclerView.NO_POSITION || holder.itemView.getTop() >= list.getPaddingTop()) return;
        if (list.getLayoutManager() instanceof LinearLayoutManager) {
            list.stopScroll();
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        }
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
            if (post.likeOwnerId != 0 && post.likeItemId > 0) {
                pm.getMenu().add(0, acts.size(), 0, R.string.bookmarks_add);
                acts.add("bookmark");
            }
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
            case "bookmark":
                String key = post.accessKey == null ? "" : post.accessKey;
                com.vktea.bookmarks.VKBookmarksApi.get(ctx, "fave.addPost", "owner_id=" + oid + "&id=" + iid
                        + (key.isEmpty() ? "" : "&access_key=" + Uri.encode(key)), new VKApi.Callback() {
                    @Override public void success(JSONObject response) {
                        Toast.makeText(ctx, response.optInt("response") == 1 ? R.string.bookmarks_added : R.string.action_failed, Toast.LENGTH_SHORT).show();
                    }
                    @Override public void error(Exception ignored) {
                        Toast.makeText(ctx, R.string.action_failed, Toast.LENGTH_SHORT).show();
                    }
                });
                break;
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
        PostPhotosRenderer.render(holder.repostPhotos, post.repostPhotos, post.repostPhotoDetails);
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

    @Override
    public int getItemCount() {
        return posts.size();
    }

    @Override
    public void onViewRecycled(@NonNull PostViewHolder holder) {
        holder.boundPost = null;
        holder.content.reset();
        holder.more.setVisibility(View.GONE);
        holder.collapseTop.setVisibility(View.GONE);
        holder.collapseTop.setTranslationY(0);
        for (View view : new View[]{holder.more, holder.collapseTop, holder.text, holder.itemView, holder.likes, holder.comments,
                holder.reposts, holder.header, holder.options, holder.showComments, holder.repostBlock, holder.videoBlock})
            view.setOnClickListener(null);
        PostPhotosRenderer.render(holder.photoContainer, null);
        PostPhotosRenderer.render(holder.repostPhotos, null);
        super.onViewRecycled(holder);
    }

    static class PostViewHolder extends RecyclerView.ViewHolder {

        final ImageView avatar;
        final FeedPostContent content;
        Post boundPost;
        final TextView name;
        final TextView time;
        final TextView text;
        final LinearLayout photoContainer;
        final TextView more;
        final TextView collapseTop;
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
            content = itemView.findViewById(R.id.post_content);
            name = itemView.findViewById(R.id.post_name);
            time = itemView.findViewById(R.id.post_time);
            text = itemView.findViewById(R.id.post_text);
            photoContainer = itemView.findViewById(R.id.post_photos);
            more = itemView.findViewById(R.id.post_more);
            collapseTop = itemView.findViewById(R.id.post_collapse_top);
            itemView.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> updateStickyCollapse(this));
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
