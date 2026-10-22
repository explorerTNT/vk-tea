package com.vktea.feed;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostActivity extends AppCompatActivity {

    public static void start(android.content.Context ctx, Post post) {
        Intent intent = new Intent(ctx, PostActivity.class);
        intent.putExtra("type", post.likeType);
        intent.putExtra("ownerId", post.likeOwnerId);
        intent.putExtra("itemId", post.likeItemId);
        intent.putExtra("name", post.sourceName);
        intent.putExtra("photo", post.sourcePhoto);
        intent.putExtra("date", post.date);
        intent.putExtra("text", post.text);
        intent.putStringArrayListExtra("photos", new ArrayList<>(post.photoUrls));
        intent.putExtra("likes", post.likes);
        intent.putExtra("comments", post.comments);
        intent.putExtra("reposts", post.reposts);
        intent.putExtra("views", post.views);
        intent.putExtra("userLikes", post.userLikes);
        ctx.startActivity(intent);
    }

    private int type;
    private long ownerId;
    private long itemId;
    private PostAdapter adapter;
    private RecyclerView list;

    private int likes;
    private boolean userLikes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post);

        type = getIntent().getIntExtra("type", Post.TYPE_POST);
        ownerId = getIntent().getLongExtra("ownerId", 0);
        itemId = getIntent().getLongExtra("itemId", 0);
        likes = getIntent().getIntExtra("likes", 0);
        userLikes = getIntent().getBooleanExtra("userLikes", false);

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
        return type == Post.TYPE_PHOTO ? "photos.getComments" : "wall.getComments";
    }

    private String idParam() {
        return type == Post.TYPE_PHOTO ? "photo_id" : "post_id";
    }

    private void loadComments() {
        VKApi.get(this, listMethod(),
                "owner_id=" + ownerId + "&" + idParam() + "=" + itemId + "&need_likes=1&count=50&extended=1&sort=asc",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
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
                                comments.add(new Comment(src[0], src[1], c.optString("text", ""),
                                        c.optLong("date", 0), cl != null ? cl.optInt("count", 0) : 0));
                            }
                            runOnUiThread(() -> adapter.setComments(comments));
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private void sendComment(String text) {
        String method = type == Post.TYPE_PHOTO ? "photos.createComment" : "wall.createComment";
        VKApi.get(this, method,
                "owner_id=" + ownerId + "&" + idParam() + "=" + itemId + "&message=" + urlEncode(text),
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        loadComments();
                    }

                    @Override
                    public void error(Exception e) {
                        runOnUiThread(() -> Toast.makeText(PostActivity.this,
                                R.string.send_failed, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private void toggleLike(View view) {
        String typeStr = type == Post.TYPE_PHOTO ? "photo" : "post";
        String method = userLikes ? "likes.delete" : "likes.add";
        userLikes = !userLikes;
        likes += userLikes ? 1 : -1;
        adapter.notifyItemChanged(0);
        VKApi.get(this, method, "type=" + typeStr + "&owner_id=" + ownerId + "&item_id=" + itemId,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                    }

                    @Override
                    public void error(Exception e) {
                        userLikes = !userLikes;
                        likes += userLikes ? 1 : -1;
                        runOnUiThread(() -> adapter.notifyItemChanged(0));
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
        final String author;
        final String avatar;
        final String text;
        final long date;
        final int likes;

        Comment(String author, String avatar, String text, long date, int likes) {
            this.author = author;
            this.avatar = avatar;
            this.text = text;
            this.date = date;
            this.likes = likes;
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
                ch.text.setText(c.text);
                ch.time.setText(com.vktea.util.RuDate.format(c.date));
                ch.likes.setText(c.likes > 0 ? FeedAdapter.formatCount(c.likes) : "");
                if (!c.avatar.isEmpty()) {
                    Glide.with(ch.avatar).load(c.avatar)
                            .apply(RequestOptions.circleCropTransform())
                            .into(ch.avatar);
                }
            }
        }

        private void bindPostCard(PostCardHolder holder) {
            Intent data = getIntent();
            holder.name.setText(data.getStringExtra("name"));
            holder.time.setText(com.vktea.util.RuDate.format(data.getLongExtra("date", 0)));

            String text = data.getStringExtra("text");
            holder.text.setText(text != null ? text : "");
            holder.text.setMaxLines(Integer.MAX_VALUE);
            holder.more.setVisibility(View.GONE);
            holder.text.setOnClickListener(null);
            holder.header.setOnClickListener(null);

            holder.photoContainer.removeAllViews();
            List<String> photos = data.getStringArrayListExtra("photos");
            if (photos != null && !photos.isEmpty()) {
                holder.photoContainer.setVisibility(View.VISIBLE);
                appendPhotos(holder.photoContainer, photos);
            } else {
                holder.photoContainer.setVisibility(View.GONE);
            }

            String srcPhoto = data.getStringExtra("photo");
            if (srcPhoto != null) {
                Glide.with(holder.avatar).load(srcPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .into(holder.avatar);
            }

            int color = ContextCompat.getColor(PostActivity.this,
                    userLikes ? R.color.vk_blue : R.color.post_icons);
            holder.likes.setTextColor(color);
            holder.likes.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
            holder.likes.setText(likes > 0 ? FeedAdapter.formatCount(likes) : "");
            holder.likes.setOnClickListener(v -> toggleLike(v));
            int commentsCount = data.getIntExtra("comments", 0);
            holder.comments.setText(commentsCount > 0 ? FeedAdapter.formatCount(commentsCount) : "");
            holder.comments.setOnClickListener(null);
            int repostsCount = data.getIntExtra("reposts", 0);
            holder.reposts.setText(repostsCount > 0 ? FeedAdapter.formatCount(repostsCount) : "");
            int viewsCount = data.getIntExtra("views", 0);
            holder.views.setText(viewsCount > 0 ? FeedAdapter.formatCount(viewsCount) : "");
        }

        private void appendPhotos(ViewGroup container, List<String> urls) {
            android.content.Context ctx = container.getContext();
            int n = urls.size();
            if (n == 1) {
                ImageView iv = new ImageView(ctx);
                iv.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Glide.with(iv).load(urls.get(0))
                        .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(iv);
                container.addView(iv);
                return;
            }
            int cols = n >= 3 ? 3 : 2;
            int rows = (n + cols - 1) / cols;
            int cell = ctx.getResources().getDisplayMetrics().widthPixels / cols;
            for (int r = 0; r < rows; r++) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    if (idx >= n) {
                        android.view.View spacer = new android.view.View(ctx);
                        spacer.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, cell, 1f));
                        row.addView(spacer);
                        continue;
                    }
                    ImageView iv = new ImageView(ctx);
                    android.widget.LinearLayout.LayoutParams lp =
                            new android.widget.LinearLayout.LayoutParams(0, cell, 1f);
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Glide.with(iv).load(urls.get(idx)).into(iv);
                    row.addView(iv);
                }
                container.addView(row);
            }
        }

        @Override
        public int getItemCount() {
            return comments.size() + 1;
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
        final ViewGroup photoContainer;
        final TextView more;
        final TextView likes;
        final TextView comments;
        final TextView reposts;
        final TextView views;
        final View header;

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
