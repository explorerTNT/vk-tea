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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.msg.ChatActivity;
import com.vktea.msg.MessageAvatars;

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
    private SwipeRefreshLayout refresh;
    private RecyclerView recycler;
    private ProfileAdapter adapter;
    private boolean profileLoading, wallLoading;
    private int requestVersion;

    public static ProfileFragment newInstance(long userId) {
        Bundle args = new Bundle();
        args.putLong(ARG_USER_ID, userId);
        ProfileFragment fragment = new ProfileFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public int navigationTitleRes() {
        return requireArguments().getLong(ARG_USER_ID) < 0 ? R.string.groups_profile_title : R.string.profile_title;
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
        refresh = view.findViewById(R.id.profile_refresh);
        refresh.setColorSchemeResources(R.color.vk_blue);
        recycler = view.findViewById(R.id.profile_wall);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ProfileAdapter();
        recycler.setAdapter(adapter);
        refresh.setOnRefreshListener(this::refreshContent);
        refreshContent();
    }

    void refreshContent() {
        if (adapter == null || refresh == null || profileLoading || wallLoading) return;
        int version = ++requestVersion;
        profileLoading = wallLoading = true;
        refresh.setRefreshing(true);
        loadProfile(adapter, version);
        loadWall(adapter, version);
    }

    private boolean current(ProfileAdapter target, int version) {
        return refresh != null && target == adapter && version == requestVersion;
    }

    private void updateRefreshing() {
        if (refresh != null) refresh.setRefreshing(profileLoading || wallLoading);
    }

    private void loadProfile(ProfileAdapter adapter, int version) {
        requestProfile(new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        if (!current(adapter, version)) return;
                        try {
                            JSONArray rows = response.optJSONArray("response");
                            if (rows == null && userId < 0) rows = response.getJSONObject("response").getJSONArray("groups");
                            JSONObject u = rows.getJSONObject(0);
                            if (userId < 0 && u.optLong("id", 0) != -userId) throw new IllegalArgumentException("Unexpected community");
                            adapter.setProfile(u);
                        } catch (Exception ignored) {
                        } finally {
                            profileLoading = false;
                            updateRefreshing();
                        }
                    }

                    @Override
                    public void error(Exception e) {
                        if (!current(adapter, version)) return;
                        profileLoading = false;
                        updateRefreshing();
                    }
                });
    }

    private void loadWall(ProfileAdapter adapter, int version) {
        requestWall(new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        if (!current(adapter, version)) return;
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
                                try {
                                    JSONObject item = items.getJSONObject(i);
                                    long sid = item.optLong("from_id", userId);
                                    String[] src = sources.containsKey(sid) ? sources.get(sid) : new String[]{"", ""};
                                    if (src[0].isEmpty() && sources.containsKey(userId)) {
                                        src = sources.get(userId);
                                    }
                                    java.util.List<String> photoUrls = new ArrayList<>();
                                    java.util.List<ViewerPhoto> photoDetails = new ArrayList<>();
                                    JSONArray attachments = item.optJSONArray("attachments");
                                    if (attachments != null) {
                                        for (int a = 0; a < attachments.length() && photoUrls.size() < 10; a++) {
                                            JSONObject att = attachments.getJSONObject(a);
                                            if ("photo".equals(att.optString("type"))) {
                                                ViewerPhoto photo = ViewerPhoto.parse(att.getJSONObject("photo"));
                                                String purl = photo.url;
                                                if (!purl.isEmpty()) {
                                                    photoUrls.add(purl);
                                                    photoDetails.add(photo);
                                                }
                                            }
                                        }
                                    }
                                    JSONObject likes = item.optJSONObject("likes");
                                    JSONObject comments = item.optJSONObject("comments");
                                    JSONObject reposts = item.optJSONObject("reposts");
                                    JSONObject views = item.optJSONObject("views");
                                    Post post = new Post(sid, src[0], src[1],
                                            item.optString("text", ""), item.optLong("date", 0), photoUrls,
                                            likes != null ? likes.optInt("count", 0) : 0,
                                            comments != null ? comments.optInt("count", 0) : 0,
                                            reposts != null ? reposts.optInt("count", 0) : 0,
                                            views != null ? views.optInt("count", 0) : 0,
                                            likes != null && likes.optInt("user_likes", 0) == 1,
                                            Post.TYPE_POST, item.optLong("owner_id", userId), item.optLong("id", 0));
                                    post.photoDetails.addAll(photoDetails);
                                    post.readShareMetadata(item);
                                    posts.add(post);
                                } catch (Exception itemError) {
                                    android.util.Log.w("VKTea", "profile wall item " + i + " skipped", itemError);
                                }
                            }
                            adapter.setWall(posts);
                        } catch (Exception ignored) {
                            showRefreshError();
                        } finally {
                            wallLoading = false;
                            updateRefreshing();
                        }
                    }

                    @Override
                    public void error(Exception e) {
                        if (!current(adapter, version)) return;
                        wallLoading = false;
                        updateRefreshing();
                        showRefreshError();
                    }
                });
    }

    private void showRefreshError() {
        if (getContext() != null) Toast.makeText(getContext(), R.string.profile_refresh_error, Toast.LENGTH_SHORT).show();
    }

    @Override public void onDestroyView() {
        requestVersion++;
        profileLoading = wallLoading = false;
        if (refresh != null) refresh.setOnRefreshListener(null);
        if (recycler != null) recycler.setAdapter(null);
        refresh = null;
        recycler = null;
        adapter = null;
        super.onDestroyView();
    }

    protected void requestProfile(VKApi.Callback callback) {
        if (userId < 0) {
            VKApi.get(requireContext(), "groups.getById", "group_id=" + (-userId)
                    + "&fields=members_count,activity,description,cover", callback);
            return;
        }
        VKApi.get(requireContext(), "users.get", "user_ids=" + userId
                + "&fields=photo_200,photo_max_orig,status,last_seen,counters,friend_status,online,bdate,city,cover",
                callback);
    }

    protected void requestWall(VKApi.Callback callback) {
        VKApi.get(requireContext(), "wall.get", "owner_id=" + userId
                + "&count=20&extended=1&photo_sizes=1", callback);
    }

    private String pickFromSizes(JSONObject photo) {
        return PostPhoto.bestUrl(photo);
    }

    class ProfileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEAD = 0;
        private static final int TYPE_POST = 1;

        private JSONObject profile;
        private final List<Post> wall = new ArrayList<>();

        void setProfile(JSONObject p) {
            boolean added = profile == null;
            profile = p;
            if (added) notifyItemInserted(0); else notifyItemChanged(0);
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
                PostPhotosRenderer.render(h.photoContainer, post.photoUrls, post.photoDetails);
                h.reposts.setText(post.reposts > 0 ? FeedAdapter.formatCount(post.reposts) : "");
                h.reposts.setEnabled(PostSharing.attachment(post).hasIdentity());
                h.reposts.setOnClickListener(v -> PostSharing.open(v.getContext(), post));
            }
        }

        private void bindHead(HeadHolder h) {
            if (profile == null) {
                return;
            }
            if (userId < 0) {
                bindGroupHead(h);
                return;
            }
            h.buttons.setVisibility(View.VISIBLE);
            h.messageBtn.setVisibility(View.VISIBLE);
            h.friendBtn.setVisibility(View.VISIBLE);
            h.name.setText(profile.optString("first_name") + " " + profile.optString("last_name"));
            String avatarUrl = profile.optString("photo_max_orig",
                    profile.optString("photo_200", ""));
            if (!avatarUrl.isEmpty()) {
                Glide.with(h.cover).load(avatarUrl)
                        .apply(RequestOptions.circleCropTransform())
                        .into(h.avatar);
            }
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
            String coverUrl = null;
            JSONObject coverObj = profile.optJSONObject("cover");
            if (coverObj != null && coverObj.optInt("enabled", 0) == 1) {
                JSONArray images = coverObj.optJSONArray("images");
                if (images != null && images.length() > 0) {
                    coverUrl = images.optJSONObject(images.length() - 1).optString("url", null);
                }
            }
            String photoMax = profile.optString("photo_max_orig", null);
            if (coverUrl == null || coverUrl.isEmpty()) {
                coverUrl = photoMax;
            }
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(h.cover).load(coverUrl).centerCrop().into(h.cover);
            }
            Button friendBtn = h.friendBtn;
            if (String.valueOf(userId).equals(com.vktea.auth.Session.getUserId(requireContext()))) {
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

        private void bindGroupHead(HeadHolder h) {
            h.name.setText(profile.optString("name", getString(R.string.groups_profile_title)));
            MessageAvatars.bind(h.avatar, MessageAvatars.photoUrl(profile), true);
            h.lastSeen.setText(profile.optString("activity", ""));
            h.status.setText(profile.optString("description", ""));
            h.status.setMaxLines(4);
            h.status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.buttons.setVisibility(View.GONE);
            h.friendBtn.setOnClickListener(null);
            h.messageBtn.setOnClickListener(null);
            Glide.with(h.cover).clear(h.cover);
            h.cover.setImageResource(R.drawable.bg_profile_cover);
            JSONObject cover = profile.optJSONObject("cover");
            if (cover != null && cover.optInt("enabled", 0) == 1) {
                JSONArray images = cover.optJSONArray("images");
                if (images != null && images.length() > 0) {
                    JSONObject image = images.optJSONObject(images.length() - 1);
                    String url = image == null ? "" : image.optString("url", "");
                    if (!url.isEmpty()) Glide.with(h.cover).load(url).centerCrop().into(h.cover);
                }
            }
            h.counters.removeAllViews();
            long members = profile.optLong("members_count", -1);
            if (members >= 0) addCounter(h.counters, R.drawable.ic_menu_followers,
                    getString(R.string.groups_members_count, Long.toString(members)));
        }

        private void addCounter(LinearLayout parent, int icon, String text) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, 0, 0);
            ImageView iconView = new ImageView(parent.getContext());
            iconView.setImageResource(icon);
            iconView.setColorFilter(0xFF6A6F75);
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
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof PostHolder) {
                PostPhotosRenderer.render(((PostHolder) holder).photoContainer, null);
            }
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemViewType(int position) {
            return profile != null && position == 0 ? TYPE_HEAD : TYPE_POST;
        }
    }

    static class HeadHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView lastSeen;
        final TextView status;
        final View buttons;
        final Button messageBtn;
        final Button friendBtn;
        final LinearLayout counters;
        final ImageView cover;
        final ImageView avatar;

        HeadHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.profile_name);
            lastSeen = itemView.findViewById(R.id.profile_last_seen);
            status = itemView.findViewById(R.id.profile_status);
            buttons = itemView.findViewById(R.id.profile_buttons_wrap);
            messageBtn = itemView.findViewById(R.id.profile_btn_message);
            friendBtn = itemView.findViewById(R.id.profile_btn_friend);
            counters = itemView.findViewById(R.id.profile_counters);
            cover = itemView.findViewById(R.id.cover_photo);
            avatar = itemView.findViewById(R.id.profile_avatar);
        }
    }

    static class PostHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView time;
        final TextView text;
        final LinearLayout photoContainer;
        final TextView reposts;

        PostHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.post_name);
            time = itemView.findViewById(R.id.post_time);
            text = itemView.findViewById(R.id.post_text);
            photoContainer = itemView.findViewById(R.id.post_photos);
            reposts = itemView.findViewById(R.id.post_reposts);
        }
    }
}
