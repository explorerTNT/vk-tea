package com.vktea.feed;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.msg.ChatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProfileFragment extends Fragment {

    private static final String ARG_USER_ID = "user_id";

    private long userId;
    private boolean isFriend;
    private int friendStatus = -1;

    public static ProfileFragment newInstance(long userId) {
        Bundle args = new Bundle();
        args.putLong(ARG_USER_ID, userId);
        ProfileFragment fragment = new ProfileFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        userId = requireArguments().getLong(ARG_USER_ID);
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recycler = view.findViewById(R.id.profile_wall);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        ProfileAdapter adapter = new ProfileAdapter();
        recycler.setAdapter(adapter);
        loadProfile(adapter);
        loadWall(adapter);
    }

    private void loadProfile(ProfileAdapter adapter) {
        VKApi.get(requireContext(), "users.get", "user_ids=" + userId
                        + "&fields=photo_200,status,last_seen,counters,friend_status,online,bdate,city",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject u = response.getJSONArray("response").getJSONObject(0);
                            adapter.setProfile(u);
                            friendStatus = u.optInt("friend_status", 0);
                            isFriend = friendStatus == 3;
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private void loadWall(ProfileAdapter adapter) {
        VKApi.get(requireContext(), "wall.get", "owner_id=" + userId + "&count=20&extended=1",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject data = response.getJSONObject("response");
                            Map<Long, String[]> sources = new HashMap<>();
                            JSONArray profiles = data.optJSONArray("profiles");
                            if (profiles != null) {
                                for (int i = 0; i < profiles.length(); i++) {
                                    JSONObject p = profiles.getJSONObject(i);
                                    sources.put(p.getLong("id"), new String[]{
                                            p.optString("first_name") + " " + p.optString("last_name"),
                                            p.optString("photo_100", "")});
                                }
                            }
                            JSONArray groups = data.optJSONArray("groups");
                            if (groups != null) {
                                for (int i = 0; i < groups.length(); i++) {
                                    JSONObject g = groups.getJSONObject(i);
                                    sources.put(-g.getLong("id"), new String[]{
                                            g.optString("name"), g.optString("photo_100", "")});
                                }
                            }
                            List<Post> posts = new ArrayList<>();
                            JSONArray items = data.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                long sid = item.optLong("from_id", userId);
                                String[] src = sources.containsKey(sid) ? sources.get(sid) : new String[]{"", ""};
                                java.util.List<String> photoUrls = new ArrayList<>();
                                JSONArray attachments = item.optJSONArray("attachments");
                                if (attachments != null) {
                                    for (int a = 0; a < attachments.length() && photoUrls.size() < 10; a++) {
                                        JSONObject att = attachments.getJSONObject(a);
                                        if ("photo".equals(att.optString("type"))) {
                                            String purl = pickFromSizes(att.getJSONObject("photo"));
                                            if (purl != null) {
                                                photoUrls.add(purl);
                                            }
                                        }
                                    }
                                }
                                JSONObject likes = item.optJSONObject("likes");
                                JSONObject comments = item.optJSONObject("comments");
                                JSONObject reposts = item.optJSONObject("reposts");
                                JSONObject views = item.optJSONObject("views");
                                posts.add(new Post(sid, src[0], src[1],
                                        item.optString("text", ""), item.optLong("date", 0), photoUrls,
                                        likes != null ? likes.optInt("count", 0) : 0,
                                        comments != null ? comments.optInt("count", 0) : 0,
                                        reposts != null ? reposts.optInt("count", 0) : 0,
                                        views != null ? views.optInt("count", 0) : 0,
                                        likes != null && likes.optInt("user_likes", 0) == 1,
                                        Post.TYPE_POST, sid, item.optLong("id", 0)));
                            }
                            adapter.setWall(posts);
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private String pickFromSizes(JSONObject photo) {
        JSONArray sizes = photo.optJSONArray("sizes");
        if (sizes == null || sizes.length() == 0) {
            return photo.optString("photo_604", null);
        }
        String best = null;
        int bestRank = -1;
        String[] order = {"w", "z", "y", "r", "q", "p", "x", "m", "s"};
        for (int i = 0; i < sizes.length(); i++) {
            JSONObject s = sizes.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String type = s.optString("type", "");
            for (int t = 0; t < order.length; t++) {
                if (order[t].equals(type) && t > bestRank) {
                    bestRank = t;
                    best = s.optString("url", null);
                }
            }
        }
        if (best != null) {
            best = best.replaceAll("&cs=\\d+x\\d+", "");
        }
        return best;
    }

    class ProfileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEAD = 0;
        private static final int TYPE_POST = 1;

        private JSONObject profile;
        private final List<Post> wall = new ArrayList<>();

        void setProfile(JSONObject p) {
            profile = p;
            notifyItemChanged(0);
        }

        void setWall(List<Post> posts) {
            wall.clear();
            wall.addAll(posts);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEAD) {
                return new HeadHolder(inflater.inflate(R.layout.item_profile_head, parent, false));
            }
            return new PostHolder(inflater.inflate(R.layout.item_post, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeadHolder) {
                bindHead((HeadHolder) holder);
            } else if (holder instanceof PostHolder) {
                Post post = wall.get(position - (profile != null ? 1 : 0));
                PostHolder h = (PostHolder) holder;
                h.name.setText(post.sourceName);
                h.time.setText(com.vktea.util.RuDate.format(post.date));
                h.text.setText(post.text);
                h.photoContainer.removeAllViews();
                if (!post.photoUrls.isEmpty()) {
                    h.photoContainer.setVisibility(View.VISIBLE);
                    int cell = h.photoContainer.getContext().getResources().getDisplayMetrics().widthPixels / 3;
                    for (int r = 0; r < (post.photoUrls.size() + 2) / 3; r++) {
                        LinearLayout row = new LinearLayout(h.photoContainer.getContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        for (int c = 0; c < 3; c++) {
                            int idx = r * 3 + c;
                            if (idx >= post.photoUrls.size()) {
                                break;
                            }
                            ImageView iv = new ImageView(h.photoContainer.getContext());
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cell, 1f);
                            iv.setLayoutParams(lp);
                            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            Glide.with(iv).load(post.photoUrls.get(idx))
                                    .override(cell, cell).into(iv);
                            row.addView(iv);
                        }
                        h.photoContainer.addView(row);
                    }
                } else {
                    h.photoContainer.setVisibility(View.GONE);
                }
            }
        }

        private void bindHead(HeadHolder h) {
            if (profile == null) {
                return;
            }
            h.name.setText(profile.optString("first_name") + " " + profile.optString("last_name"));
            boolean online = profile.optInt("online", 0) == 1;
            if (online) {
                h.lastSeen.setText(R.string.online_now);
            } else {
                JSONObject lastSeen = profile.optJSONObject("last_seen");
                if (lastSeen != null) {
                    h.lastSeen.setText(getString(R.string.last_seen_fmt,
                            new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(
                                    new Date(lastSeen.optLong("time", 0) * 1000L))));
                }
            }
            Button friendBtn = h.friendBtn;
            if (userId == Long.parseLong(com.vktea.auth.Session.getUserId(requireContext()))) {
                friendBtn.setVisibility(View.GONE);
            } else if (isFriend) {
                friendBtn.setText(R.string.is_friend);
                friendBtn.setOnClickListener(v -> VKApi.get(requireContext(), "friends.delete",
                        "user_id=" + userId, new VKApi.Callback() {
                            @Override
                            public void success(JSONObject response) {
                                isFriend = false;
                                requireActivity().runOnUiThread(() -> notifyItemChanged(0));
                            }

                            @Override
                            public void error(Exception e) {
                            }
                        }));
            } else {
                friendBtn.setText(R.string.add_friend);
                friendBtn.setOnClickListener(v -> VKApi.get(requireContext(), "friends.add",
                        "user_id=" + userId, new VKApi.Callback() {
                            @Override
                            public void success(JSONObject response) {
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), R.string.request_sent, Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void error(Exception e) {
                            }
                        }));
            }
            h.messageBtn.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), ChatActivity.class)
                            .putExtra(ChatActivity.EXTRA_PEER_ID, userId)
                            .putExtra(ChatActivity.EXTRA_TITLE, h.name.getText())));

            LinearLayout counters = h.counters;
            counters.removeAllViews();
            JSONObject c = profile.optJSONObject("counters");
            if (c != null) {
                addCounter(counters, R.drawable.ic_menu_friends,
                        getString(R.string.friends_count_fmt, c.optInt("friends", 0)));
                addCounter(counters, R.drawable.ic_menu_photos,
                        getString(R.string.photos_count_fmt, c.optInt("photos", 0)));
                addCounter(counters, R.drawable.ic_menu_videos,
                        getString(R.string.videos_count_fmt, c.optInt("videos", 0)));
                addCounter(counters, R.drawable.ic_menu_followers,
                        getString(R.string.followers_count_fmt, c.optInt("followers", 0)));
            }
        }

        private void addCounter(LinearLayout parent, int icon, String text) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, 0, 0);
            ImageView iconView = new ImageView(parent.getContext());
            iconView.setImageResource(icon);
            iconView.setAlpha(0.6f);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    (int) (24 * getResources().getDisplayMetrics().density),
                    (int) (24 * getResources().getDisplayMetrics().density));
            int m = (int) (8 * getResources().getDisplayMetrics().density);
            iconLp.setMargins(0, m, m, m);
            iconView.setLayoutParams(iconLp);
            row.addView(iconView);
            TextView label = new TextView(parent.getContext());
            label.setText(text);
            label.setTextColor(0xFF6A6F75);
            label.setTextSize(14);
            label.setPadding(0, m, m, m);
            row.addView(label);
            parent.addView(row);
        }

        @Override
        public int getItemCount() {
            return wall.size() + (profile != null ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return profile != null && position == 0 ? TYPE_HEAD : TYPE_POST;
        }
    }

    static class HeadHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView lastSeen;
        final Button messageBtn;
        final Button friendBtn;
        final LinearLayout counters;

        HeadHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.profile_name);
            lastSeen = itemView.findViewById(R.id.profile_last_seen);
            messageBtn = itemView.findViewById(R.id.profile_btn_message);
            friendBtn = itemView.findViewById(R.id.profile_btn_friend);
            counters = itemView.findViewById(R.id.profile_counters);
        }
    }

    static class PostHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView time;
        final TextView text;
        final LinearLayout photoContainer;

        PostHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.post_name);
            time = itemView.findViewById(R.id.post_time);
            text = itemView.findViewById(R.id.post_text);
            photoContainer = itemView.findViewById(R.id.post_photos);
        }
    }
}
