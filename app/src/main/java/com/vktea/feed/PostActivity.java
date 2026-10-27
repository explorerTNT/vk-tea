package com.vktea.feed;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
 import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.util.RuDate;
import com.vktea.util.VkTextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostActivity extends com.vktea.ui.TeaActivity {

    public static Post currentPost;

    public static void start(android.content.Context ctx, Post post) {
        currentPost = post;
        ctx.startActivity(new Intent(ctx, PostActivity.class));
    }

    private Post post;
    private PostAdapter adapter;
    private RecyclerView list;
    private long replyToComment = 0;
    private String replyToName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean pendingReveal = com.vktea.auth.Session.hasPendingReveal(this);
        super.onCreate(pendingReveal ? null : savedInstanceState);
        if (com.vktea.auth.TokenRevealActivity.redirectIfPending(this)) return;
        setContentView(R.layout.activity_post);

        post = currentPost;
        if (post == null) {
            finish();
            return;
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.post_toolbar);
        toolbar.setTitle(R.string.post_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.post_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PostAdapter();
        list.setAdapter(adapter);

        View send = findViewById(R.id.post_send);
        EditText input = findViewById(R.id.post_input);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            sendComment(text);
        });

        loadComments();
    }

    private String listMethod() {
        return post.likeType == Post.TYPE_PHOTO ? "photos.getComments" : "wall.getComments";
    }

    private String idParam() {
        return post.likeType == Post.TYPE_PHOTO ? "photo_id" : "post_id";
    }

    private void loadComments() {
        requestComments(new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                applyComments(response);
            }

            @Override
            public void error(Exception e) {
            }
        });
    }

    protected void requestComments(VKApi.Callback callback) {
        VKApi.get(this, listMethod(),
                "owner_id=" + post.likeOwnerId + "&" + idParam() + "=" + post.likeItemId
                        + "&need_likes=1&count=50&extended=1&sort=asc",
                callback);
    }

    private void applyComments(JSONObject response) {
        try {
            JSONObject data = response.getJSONObject("response");
            Map<Long, String[]> profiles = new HashMap<>();
            JSONArray parr = data.optJSONArray("profiles");
            if (parr != null) {
                for (int i = 0; i < parr.length(); i++) {
                    JSONObject p = parr.getJSONObject(i);
                    profiles.put(p.getLong("id"), new String[]{
                            p.optString("first_name") + " " + p.optString("last_name"),
                            p.optString("photo_100", "")});
                }
            }
            JSONArray garr = data.optJSONArray("groups");
            if (garr != null) {
                for (int i = 0; i < garr.length(); i++) {
                    JSONObject g = garr.getJSONObject(i);
                    profiles.put(-g.getLong("id"), new String[]{
                            g.optString("name"), g.optString("photo_100", "")});
                }
            }
            List<Comment> comments = new ArrayList<>();
            JSONArray items = data.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject c = items.getJSONObject(i);
                long fromId = c.optLong("from_id", 0);
                String[] src = profiles.containsKey(fromId) ? profiles.get(fromId) : new String[]{"", ""};
                JSONObject cl = c.optJSONObject("likes");
                comments.add(new Comment(c.getLong("id"), fromId,
                        src[0], src[1], c.optString("text", ""),
                        c.optLong("date", 0),
                        cl != null ? cl.optInt("count", 0) : 0,
                        cl != null && cl.optInt("user_likes", 0) == 1));
            }
            runOnUiThread(() -> {
                adapter.setComments(comments);
                if (!comments.isEmpty()) {
                    list.scrollToPosition(comments.size());
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void sendComment(String text) {
        String method = post.likeType == Post.TYPE_PHOTO ? "photos.createComment" : "wall.createComment";
        VKApi.get(this, method,
                "owner_id=" + post.likeOwnerId + "&" + idParam() + "=" + post.likeItemId
                        + (replyToComment > 0 ? "&reply_to_comment=" + replyToComment : "")
                        + "&message=" + urlEncode(text),
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        replyToComment = 0;
                        replyToName = "";
                        loadComments();
                    }

                    @Override
                    public void error(Exception e) {
                        runOnUiThread(() -> Toast.makeText(PostActivity.this,
                                R.string.send_failed, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static class Comment {
        final long id;
        final long fromId;
        final String author;
        final String avatar;
        final String text;
        final long date;
        int likes;
        boolean liked;

        Comment(long id, long fromId, String author, String avatar, String text,
                long date, int likes, boolean liked) {
            this.id = id;
            this.fromId = fromId;
            this.author = author;
            this.avatar = avatar;
            this.text = text;
            this.date = date;
            this.likes = likes;
            this.liked = liked;
        }
    }

    class PostAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_POST = 0;
        private static final int TYPE_COMMENT = 1;

        private final List<Comment> comments = new ArrayList<>();

        void setComments(List<Comment> newComments) {
            comments.clear();
            comments.addAll(newComments);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_POST) {
                return new PostCardHolder(inflater.inflate(R.layout.item_post, parent, false));
            }
            return new CommentHolder(inflater.inflate(R.layout.item_comment, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof PostCardHolder) {
                bindPostCard((PostCardHolder) holder);
            } else {
                Comment c = comments.get(position - 1);
                CommentHolder ch = (CommentHolder) holder;
                ch.author.setText(c.author);
                ch.text.setText(VkTextUtils.parse(c.text,
                        com.vktea.ui.TeaColors.get(ch.text.getContext(), R.color.post_name),
                        url -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))));
                ch.text.setMovementMethod(LinkMovementMethod.getInstance());
                ch.time.setText(RuDate.format(c.date));
                ch.likes.setText(c.likes > 0 ? FeedAdapter.formatCount(c.likes) : "");
                int color = com.vktea.ui.TeaColors.get(ch.likes.getContext(),
                        c.liked ? R.color.vk_blue : R.color.post_icons);
                ch.likes.setTextColor(color);
                ch.likes.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
                ch.likes.setOnClickListener(v -> toggleCommentLike(c, ch));
                ch.itemView.setOnClickListener(v -> {
                    replyToComment = c.id;
                    replyToName = c.author;
                    EditText input = findViewById(R.id.post_input);
                    input.setText(c.author.split(" ")[0] + ", " + input.getText().toString());
                    input.requestFocus();
                });
                if (!c.avatar.isEmpty()) {
                    Glide.with(ch.avatar).load(c.avatar)
                            .apply(RequestOptions.circleCropTransform())
                            .into(ch.avatar);
                }
            }
        }

        private void toggleCommentLike(Comment c, CommentHolder ch) {
            VKApi.get(PostActivity.this, c.liked ? "likes.delete" : "likes.add",
                    "type=comment&owner_id=" + c.fromId + "&item_id=" + c.id,
                    new VKApi.Callback() {
                        @Override
                        public void success(JSONObject response) {
                            c.liked = !c.liked;
                            c.likes += c.liked ? 1 : -1;
                            runOnUiThread(() -> notifyItemChanged(comments.indexOf(c) + 1));
                        }

                        @Override
                        public void error(Exception e) {
                            Toast.makeText(PostActivity.this, R.string.action_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        private void bindPostCard(PostCardHolder holder) {
            int linkColor = com.vktea.ui.TeaColors.get(PostActivity.this, R.color.post_name);
            holder.name.setText(post.sourceName);
            holder.time.setText(RuDate.format(post.date));

            holder.text.setText(VkTextUtils.parse(post.text, linkColor,
                    url -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))));
            holder.text.setMovementMethod(LinkMovementMethod.getInstance());
            holder.text.setMaxLines(Integer.MAX_VALUE);
            holder.text.setOnClickListener(null);
            holder.more.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            holder.header.setOnClickListener(null);

            PostPhotosRenderer.render(holder.photoContainer, post.photoUrls, post.photoDetails);
            PostPhotosRenderer.render(holder.repostPhotos, post.repostPhotos, post.repostPhotoDetails);

            if (post.isRepost()) {
                holder.repostBlock.setVisibility(View.VISIBLE);
                holder.repostName.setText(post.repostAuthorName);
                holder.repostTime.setText(RuDate.format(post.repostDate));
                holder.repostText.setText(VkTextUtils.parse(post.repostText, linkColor,
                        url -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))));
                holder.repostText.setMovementMethod(LinkMovementMethod.getInstance());
                if (post.repostAuthorPhoto != null && !post.repostAuthorPhoto.isEmpty()) {
                    Glide.with(holder.repostAvatar).load(post.repostAuthorPhoto)
                            .apply(RequestOptions.circleCropTransform())
                            .into(holder.repostAvatar);
                }
            } else {
                holder.repostBlock.setVisibility(View.GONE);
            }

            if (post.hasVideo()) {
                holder.videoBlock.setVisibility(View.VISIBLE);
                Glide.with(holder.videoPreview).load(post.videoPreview).into(holder.videoPreview);
                holder.videoBlock.setOnClickListener(v ->
                        VideoPlayerActivity.start(PostActivity.this, post.videoOwner, post.videoId));
            } else {
                holder.videoBlock.setVisibility(View.GONE);
            }

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

            int color = com.vktea.ui.TeaColors.get(PostActivity.this,
                    post.userLikes ? R.color.vk_blue : R.color.post_icons);
            holder.likes.setTextColor(color);
            holder.likes.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
            holder.likes.setText(post.likes > 0 ? FeedAdapter.formatCount(post.likes) : "");
            holder.likes.setOnClickListener(v -> {
                String typeStr = post.likeType == Post.TYPE_PHOTO ? "photo" : "post";
                String method = post.userLikes ? "likes.delete" : "likes.add";
                post.userLikes = !post.userLikes;
                post.likes += post.userLikes ? 1 : -1;
                notifyItemChanged(0);
                VKApi.get(PostActivity.this, method,
                        "type=" + typeStr + "&owner_id=" + post.likeOwnerId + "&item_id=" + post.likeItemId,
                        new VKApi.Callback() {
                            @Override
                            public void success(JSONObject response) {
                            }

                            @Override
                            public void error(Exception e) {
                                post.userLikes = !post.userLikes;
                                post.likes += post.userLikes ? 1 : -1;
                                runOnUiThread(() -> notifyItemChanged(0));
                            }
                        });
            });
            holder.comments.setText(post.comments > 0 ? FeedAdapter.formatCount(post.comments) : "");
            holder.reposts.setText(post.reposts > 0 ? FeedAdapter.formatCount(post.reposts) : "");
            holder.reposts.setEnabled(PostSharing.attachment(post).hasIdentity());
            holder.reposts.setOnClickListener(view -> PostSharing.open(view.getContext(), post));
            holder.views.setText(post.views > 0 ? FeedAdapter.formatCount(post.views) : "");
            holder.showComments.setVisibility(View.GONE);
            holder.options.setOnClickListener(null);
            holder.options.setVisibility(View.INVISIBLE);
        }

        @Override
        public int getItemCount() {
            return comments.size() + 1;
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof PostCardHolder) {
                PostCardHolder card = (PostCardHolder) holder;
                PostPhotosRenderer.render(card.photoContainer, null);
                PostPhotosRenderer.render(card.repostPhotos, null);
            }
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_POST : TYPE_COMMENT;
        }
    }

    static class PostCardHolder extends RecyclerView.ViewHolder {

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

        PostCardHolder(@NonNull View itemView) {
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

    static class CommentHolder extends RecyclerView.ViewHolder {

        final ImageView avatar;
        final TextView author;
        final TextView time;
        final TextView text;
        final TextView likes;

        CommentHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.comment_avatar);
            author = itemView.findViewById(R.id.comment_author);
            time = itemView.findViewById(R.id.comment_time);
            text = itemView.findViewById(R.id.comment_text);
            likes = itemView.findViewById(R.id.comment_likes);
        }
    }
}
