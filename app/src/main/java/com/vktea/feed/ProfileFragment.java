package com.vktea.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ProfileFragment extends Fragment {

    private static final String ARG_USER_ID = "user_id";

    private long userId;
    private FeedAdapter adapter;

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
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        userId = requireArguments().getLong(ARG_USER_ID);

        RecyclerView wall = view.findViewById(R.id.profile_wall);
        wall.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FeedAdapter(sourceId -> {
            if (userId != sourceId && getActivity() instanceof com.vktea.MainActivity) {
                ((com.vktea.MainActivity) getActivity()).openFragment(
                        ProfileFragment.newInstance(sourceId), R.string.profile_title);
            }
        });
        wall.setAdapter(adapter);

        loadProfile(view);
        loadWall();
    }

    private void loadProfile(View view) {
        VKApi.get(requireContext(), "users.get", "user_ids=" + userId
                        + "&fields=photo_200,status,bdate,city,online",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject u = response.getJSONArray("response").getJSONObject(0);
                            TextView name = view.findViewById(R.id.profile_name);
                            TextView status = view.findViewById(R.id.profile_status);
                            ImageView avatar = view.findViewById(R.id.profile_avatar);
                            name.setText(u.optString("first_name") + " " + u.optString("last_name"));
                            status.setText(u.optString("status", ""));
                            Glide.with(avatar).load(u.optString("photo_200"))
                                    .apply(RequestOptions.circleCropTransform())
                                    .into(avatar);
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private void loadWall() {
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
                            java.util.List<Post> posts = new java.util.ArrayList<>();
                            JSONArray items = data.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                long sid = item.optLong("from_id", userId);
                                String[] src = sources.containsKey(sid) ? sources.get(sid)
                                        : new String[]{"", ""};
                                java.util.List<String> photoUrls = new java.util.ArrayList<>();
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
                                        com.vktea.feed.Post.TYPE_POST,
                                        sid,
                                        item.optLong("id", 0)));
                            }
                            adapter.clear();
                            adapter.addAll(posts);
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }

    private String pickFromSizes(JSONObject photo) {
        String direct = photo.optString("photo_604", null);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        JSONArray sizes = photo.optJSONArray("sizes");
        if (sizes == null || sizes.length() == 0) {
            return null;
        }
        String[] typeOrder = {"w", "z", "y", "r", "q", "p", "x", "m", "s"};
        String best = null;
        int bestRank = -1;
        for (int i = 0; i < sizes.length(); i++) {
            JSONObject s = sizes.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String type = s.optString("type", "");
            int rank = -1;
            for (int t = 0; t < typeOrder.length; t++) {
                if (typeOrder[t].equals(type)) {
                    rank = t;
                    break;
                }
            }
            if (rank > bestRank) {
                bestRank = rank;
                best = s.optString("url", null);
            }
        }
        if (best == null) {
            best = sizes.optJSONObject(sizes.length() - 1).optString("url", null);
        }
        if (best != null) {
            best = best.replaceAll("&cs=\\d+x\\d+", "");
        }
        return best;
    }
}
