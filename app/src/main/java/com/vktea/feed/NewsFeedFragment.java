package com.vktea.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewsFeedFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private FeedAdapter adapter;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private boolean loading;
    private String nextFrom = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_news, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        refresh = view.findViewById(R.id.feed_refresh);
        list = view.findViewById(R.id.feed_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FeedAdapter(sourceId -> {
            if (getActivity() instanceof com.vktea.MainActivity) {
                ((com.vktea.MainActivity) getActivity()).openFragment(
                        com.vktea.feed.ProfileFragment.newInstance(sourceId), R.string.profile_title);
            }
        });
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(() -> {
            nextFrom = "";
            load();
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading) {
                    return;
                }
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                    loading = true;
                    load();
                }
            }
        });

        refresh.setRefreshing(true);
        load();
    }

    private void load() {
        loading = true;
        String params = "filters=post,photo&count=" + PAGE_SIZE + "&max_photos=1"
                + (nextFrom.isEmpty() ? "" : "&start_from=" + nextFrom);
        VKApi.get(requireContext(), "newsfeed.get", params,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject data = response.getJSONObject("response");
                            nextFrom = data.optString("next_from", "");
                            Map<Long, Source> sources = parseSources(data);
                            List<Post> posts = new ArrayList<>();
                            JSONArray items = data.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                Post post = parsePost(items.getJSONObject(i), sources);
                                if (post != null) {
                                    posts.add(post);
                                }
                            }
                            if (refresh.isRefreshing()) {
                                adapter.clear();
                            }
                            adapter.addAll(posts);
                        } catch (Exception ignored) {
                        }
                        loading = false;
                        refresh.setRefreshing(false);
                    }

                    @Override
                    public void error(Exception e) {
                        loading = false;
                        refresh.setRefreshing(false);
                    }
                });
    }

    private Map<Long, Source> parseSources(JSONObject data) throws Exception {
        Map<Long, Source> sources = new HashMap<>();
        JSONArray profiles = data.optJSONArray("profiles");
        if (profiles != null) {
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.getJSONObject(i);
                sources.put(p.getLong("id"), new Source(
                        p.optString("first_name", "") + " " + p.optString("last_name", ""),
                        p.optString("photo_100", null)));
            }
        }
        JSONArray groups = data.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.getJSONObject(i);
                sources.put(-g.getLong("id"), new Source(
                        g.optString("name", ""),
                        g.optString("photo_100", null)));
            }
        }
        return sources;
    }

    private Post parsePost(JSONObject item, Map<Long, Source> sources) throws Exception {
        long sourceId = item.optLong("source_id", 0);
        Source source = sources.get(sourceId);
        if (source == null) {
            return null;
        }
        List<String> photoUrls = new ArrayList<>();
        JSONObject countsHolder = item;
        int likeType = Post.TYPE_POST;
        long likeOwnerId = sourceId;
        long likeItemId = item.optLong("post_id", item.optLong("id", 0));

        String type = item.optString("type", "post");
        if ("photo".equals(type)) {
            JSONObject photosWrap = item.optJSONObject("photos");
            JSONArray photoItems = photosWrap != null ? photosWrap.optJSONArray("items") : null;
            if (photoItems != null && photoItems.length() > 0) {
                JSONObject first = photoItems.getJSONObject(0);
                countsHolder = first;
                likeType = Post.TYPE_PHOTO;
                likeOwnerId = first.optLong("owner_id", sourceId);
                likeItemId = first.optLong("id", 0);
                for (int i = 0; i < photoItems.length() && photoUrls.size() < 10; i++) {
                    String u = pickPhotoUrl(photoItems.getJSONObject(i));
                    if (u != null) {
                        photoUrls.add(u);
                    }
                }
            }
        } else {
            JSONArray attachments = item.optJSONArray("attachments");
            if (attachments != null) {
                for (int i = 0; i < attachments.length(); i++) {
                    JSONObject att = attachments.getJSONObject(i);
                    if ("photo".equals(att.optString("type"))) {
                        String u = pickPhotoUrl(att.getJSONObject("photo"));
                        if (u != null && photoUrls.size() < 10) {
                            photoUrls.add(u);
                        }
                    }
                }
            }
        }

        JSONObject likes = countsHolder.optJSONObject("likes");
        JSONObject comments = countsHolder.optJSONObject("comments");
        JSONObject reposts = countsHolder.optJSONObject("reposts");
        JSONObject views = countsHolder.optJSONObject("views");
        return new Post(
                sourceId,
                source.name,
                source.photo,
                item.optString("text", ""),
                item.optLong("date", 0),
                photoUrls,
                likes != null ? likes.optInt("count", 0) : 0,
                comments != null ? comments.optInt("count", 0) : 0,
                reposts != null ? reposts.optInt("count", 0) : 0,
                views != null ? views.optInt("count", 0) : 0,
                likes != null && likes.optInt("user_likes", 0) == 1,
                likeType,
                likeOwnerId,
                likeItemId);
    }

    private String pickPhotoUrl(JSONObject photo) {
        String[] candidates = {"photo_2560", "photo_1280", "photo_807", "photo_604", "photo_130"};
        for (String key : candidates) {
            String url = photo.optString(key, null);
            if (url != null && !url.isEmpty()) {
                return url;
            }
        }
        JSONArray sizes = photo.optJSONArray("sizes");
        if (sizes != null && sizes.length() > 0) {
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
        return null;
    }

    private static class Source {
        final String name;
        final String photo;

        Source(String name, String photo) {
            this.name = name;
            this.photo = photo;
        }
    }
}
