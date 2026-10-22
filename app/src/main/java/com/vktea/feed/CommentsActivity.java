package com.vktea.feed;

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

public class CommentsActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE = "ctype";
    public static final String EXTRA_OWNER_ID = "owner_id";
    public static final String EXTRA_ITEM_ID = "item_id";

    private int type;
    private long ownerId;
    private long itemId;
    private CommentsAdapter adapter;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comments);

        type = getIntent().getIntExtra(EXTRA_TYPE, Post.TYPE_POST);
        ownerId = getIntent().getLongExtra(EXTRA_OWNER_ID, 0);
        itemId = getIntent().getLongExtra(EXTRA_ITEM_ID, 0);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.comments_toolbar);
        toolbar.setTitle(R.string.comments_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.comments_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CommentsAdapter();
        list.setAdapter(adapter);

        View send = findViewById(R.id.comments_send);
        EditText input = findViewById(R.id.comments_input);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            sendComment(text);
        });

        load();
    }

    private String listMethod() {
        return type == Post.TYPE_PHOTO ? "photos.getComments" : "wall.getComments";
    }

    private String idParam() {
        return type == Post.TYPE_PHOTO ? "photo_id" : "post_id";
    }

    private void load() {
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
                                JSONObject likes = c.optJSONObject("likes");
                                comments.add(new Comment(
                                        src[0], src[1], c.optString("text", ""),
                                        c.optLong("date", 0),
                                        likes != null ? likes.optInt("count", 0) : 0));
                            }
                            runOnUiThread(() -> {
                                adapter.replaceAll(comments);
                                if (!comments.isEmpty()) {
                                    list.scrollToPosition(comments.size() - 1);
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private void sendComment(String text) {
        String createMethod = type == Post.TYPE_PHOTO ? "photos.createComment" : "wall.createComment";
        VKApi.get(this, createMethod,
                "owner_id=" + ownerId + "&" + idParam() + "=" + itemId
                        + "&message=" + urlEncode(text),
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        load();
                    }

                    @Override
                    public void error(Exception e) {
                        runOnUiThread(() -> Toast.makeText(CommentsActivity.this,
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

    static class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.Holder> {

        private final List<Comment> comments = new ArrayList<>();

        void replaceAll(List<Comment> newComments) {
            comments.clear();
            comments.addAll(newComments);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_comment, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Comment c = comments.get(position);
            holder.author.setText(c.author);
            holder.text.setText(c.text);
            holder.time.setText(com.vktea.util.RuDate.format(c.date));
            holder.likes.setText(c.likes > 0 ? FeedAdapter.formatCount(c.likes) : "");
            if (!c.avatar.isEmpty()) {
                Glide.with(holder.avatar).load(c.avatar)
                        .apply(RequestOptions.circleCropTransform())
                        .into(holder.avatar);
            }
        }

        @Override
        public int getItemCount() {
            return comments.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView author;
            final TextView time;
            final TextView text;
            final TextView likes;

            Holder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.comment_avatar);
                author = itemView.findViewById(R.id.comment_author);
                time = itemView.findViewById(R.id.comment_time);
                text = itemView.findViewById(R.id.comment_text);
                likes = itemView.findViewById(R.id.comment_likes);
            }
        }
    }
}
