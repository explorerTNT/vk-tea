package com.vktea.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;
 import android.widget.TextView;
 import android.widget.Toast;
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

    public static NewsFeedFragment newInstance(long listId) {
        Bundle args = new Bundle();
        args.putLong("list_id", listId);
        NewsFeedFragment fragment = new NewsFeedFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private FeedAdapter adapter;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private boolean loading;
    private String nextFrom = "";
    private long listId;
    private long newestDate = 0;
    private long lastCheckTime = 0;
    private TextView newPostsButton;
    private RecyclerView storiesRow;
    private final List<Story> stories = new ArrayList<>();

    static class Story {
        final String name;
        final String avatar;
        final String link;

        Story(String name, String avatar, String link) {
            this.name = name;
            this.avatar = avatar;
            this.link = link;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_news, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listId = requireArguments().getLong("list_id", 0);
        refresh = view.findViewById(R.id.feed_refresh);
        newPostsButton = view.findViewById(R.id.feed_new_posts);
        storiesRow = view.findViewById(R.id.stories_row);
        list = view.findViewById(R.id.feed_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FeedAdapter(sourceId -> {
            if (getActivity() instanceof com.vktea.MainActivity) {
                ((com.vktea.MainActivity) getActivity()).openFragment(
                        com.vktea.feed.ProfileFragment.newInstance(sourceId), R.string.profile_title);
            }
        });
        list.setAdapter(adapter);

        newPostsButton.setOnClickListener(v -> {
            newPostsButton.setVisibility(View.GONE);
            nextFrom = "";
            refresh.setRefreshing(true);
            load();
        });

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

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findFirstVisibleItemPosition() == 0
                        && !loading && newestDate > 0
                        && System.currentTimeMillis() - lastCheckTime > 30000) {
                    checkNewPosts();
                }
            }
        });

        refresh.setRefreshing(true);
        load();
        loadStories();
    }

    private void checkNewPosts() {
        lastCheckTime = System.currentTimeMillis();
        String params = "filters=post,photo&count=10&start_time=" + newestDate
                + (listId != 0 ? "&list_id=" + listId : "");
        VKApi.get(requireContext(), "newsfeed.get", params, new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                try {
                    int n = response.getJSONObject("response").getJSONArray("items").length();
                    if (n > 0 && isAdded()) {
                        newPostsButton.setText(String.format(
                                getString(R.string.new_posts_fmt), n));
                        newPostsButton.setVisibility(View.VISIBLE);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void error(Exception e) {
            }
        });
    }

    private void loadStories() {
        VKApi.get(requireContext(), "stories.get", "extended=1", new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                try {
                    stories.clear();
                    JSONObject data = response.getJSONObject("response");
                    JSONArray countArr = data.optJSONArray("count");
                    JSONArray items = data.optJSONArray("items");
                    if (items == null || countArr == null || countArr.length() != items.length()) {
                        return;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject owner = items.getJSONObject(i);
                        JSONArray ownerStories = owner.getJSONArray("stories");
                        if (ownerStories.length() == 0) {
                            continue;
                        }
                        JSONObject first = ownerStories.getJSONObject(0);
                        String name = owner.optBoolean("is_group")
                                ? owner.optString("name")
                                : owner.optString("first_name") + " " + owner.optString("last_name");
                        stories.add(new Story(name,
                                owner.optString("photo_100", ""),
                                "https://m.vk.com/story" + first.optLong("owner_id") + "_" + first.optLong("id")));
                    }
                    if (!stories.isEmpty() && isAdded()) {
                        storiesRow.setVisibility(View.VISIBLE);
                        storiesRow.setLayoutManager(
                                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
                        storiesRow.setAdapter(new StoriesAdapter());
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void error(Exception e) {
            }
        });
    }

    class StoriesAdapter extends RecyclerView.Adapter<StoriesAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_story, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Story story = stories.get(position);
            holder.name.setText(story.name);
            if (!story.avatar.isEmpty()) {
                com.bumptech.glide.Glide.with(holder.avatar).load(story.avatar)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .into(holder.avatar);
            }
            holder.itemView.setOnClickListener(v -> {
                android.content.Intent intent = new android.content.Intent(v.getContext(),
                        com.vktea.auth.AuthWebViewActivity.class);
                intent.setData(android.net.Uri.parse(story.link));
                v.getContext().startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return stories.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView name;

            Holder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.story_avatar);
                name = itemView.findViewById(R.id.story_name);
            }
        }
    }

    private void load() {
        loading = true;
        String params = "filters=post,photo&count=" + PAGE_SIZE + "&max_photos=1&photo_sizes=1"
                + (listId != 0 ? "&list_id=" + listId : "")
                + (nextFrom.isEmpty() ? "" : "&start_from=" + nextFrom);
        VKApi.get(requireContext(), "newsfeed.get", params,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject data = response.getJSONObject("response");
                            if (nextFrom.isEmpty()) {
                                saveCache(response.toString());
                            }
                            nextFrom = data.optString("next_from", "");
                            Map<Long, Source> sources = parseSources(data);
                            List<Post> posts = new ArrayList<>();
                            JSONArray items = data.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                Post post = parsePost(items.getJSONObject(i), sources);
                                if (post != null) {
                                    posts.add(post);
                                    if (post.date > newestDate) {
                                        newestDate = post.date;
                                    }
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
                        if (adapter.getItemCount() == 0) {
                            loadCache();
                        }
                    }
                });
    }

    private void saveCache(String json) {
        try {
            java.io.File file = new java.io.File(requireContext().getFilesDir(), "feed_cache.json");
            java.io.FileWriter writer = new java.io.FileWriter(file, false);
            writer.write(json);
            writer.close();
        } catch (Exception ignored) {
        }
    }

    private void loadCache() {
        try {
            java.io.File file = new java.io.File(requireContext().getFilesDir(), "feed_cache.json");
            if (!file.exists()) {
                return;
            }
            StringBuilder json = new StringBuilder();
            try (java.io.FileReader reader = new java.io.FileReader(file)) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    json.append(buffer, 0, count);
                }
            }
            JSONObject response = new JSONObject(json.toString());
            JSONObject data = response.getJSONObject("response");
            Map<Long, Source> sources = parseSources(data);
            List<Post> posts = new ArrayList<>();
            JSONArray items = data.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                Post post = parsePost(items.getJSONObject(i), sources);
                if (post != null) {
                    posts.add(post);
                    if (post.date > newestDate) {
                        newestDate = post.date;
                    }
                }
            }
            adapter.clear();
            adapter.addAll(posts);
            Toast.makeText(requireContext(), R.string.offline_mode, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
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
        List<ViewerPhoto> photoDetails = new ArrayList<>();
        JSONObject countsHolder = item;
        int likeType = Post.TYPE_POST;
        long likeOwnerId = sourceId;
        long likeItemId = item.optLong("post_id", item.optLong("id", 0));

        String type = item.optString("type", "post");
        String videoPreview = null;
        long videoOwner = 0;
        long videoId = 0;
        List<Post.Card> cards = new ArrayList<>();
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
                    ViewerPhoto photo = ViewerPhoto.parse(photoItems.getJSONObject(i));
                    String u = photo.url.isEmpty() ? null : photo.url;
                    if (u != null) {
                        photoUrls.add(u);
                        photoDetails.add(photo);
                    }
                }
            }
        } else {
            JSONArray attachments = item.optJSONArray("attachments");
            if (attachments != null) {
                for (int i = 0; i < attachments.length(); i++) {
                    JSONObject att = attachments.getJSONObject(i);
                    String attType = att.optString("type");
                    if ("photo".equals(attType)) {
                        ViewerPhoto photo = ViewerPhoto.parse(att.getJSONObject("photo"));
                        String u = photo.url.isEmpty() ? null : photo.url;
                        if (u != null && photoUrls.size() < 10) {
                            photoUrls.add(u);
                            photoDetails.add(photo);
                        }
                    } else if ("video".equals(attType) && videoPreview == null) {
                        JSONObject video = att.getJSONObject("video");
                        String[] vc = {"photo_800", "photo_640", "photo_320", "first_frame_800", "first_frame_320"};
                        for (String key : vc) {
                            String u = video.optString(key, null);
                            if (u != null && !u.isEmpty()) {
                                videoPreview = u;
                                break;
                            }
                        }
                        videoOwner = video.optLong("owner_id", 0);
                        videoId = video.optLong("id", 0);
                    } else {
                        Post.Card card = parseCard(attType, att.optJSONObject(attType));
                        if (card != null) {
                            cards.add(card);
                        }
                    }
                }
            }
        }

        JSONObject likes = countsHolder.optJSONObject("likes");
        JSONObject comments = countsHolder.optJSONObject("comments");
        JSONObject reposts = countsHolder.optJSONObject("reposts");
        JSONObject views = countsHolder.optJSONObject("views");
        Post post = new Post(
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

        post.readShareMetadata(countsHolder);

        post.photoDetails.addAll(photoDetails);
        post.videoPreview = videoPreview;
        post.videoOwner = videoOwner;
        post.videoId = videoId;
        post.cards.addAll(cards);

        JSONArray copyHistory = item.optJSONArray("copy_history");
        if (copyHistory != null && copyHistory.length() > 0) {
            JSONObject orig = copyHistory.getJSONObject(0);
            long fromId = orig.optLong("from_id", orig.optLong("owner_id", 0));
            Source origSource = sources.get(fromId);
            if (origSource != null) {
                post.repostAuthorName = origSource.name;
                post.repostAuthorPhoto = origSource.photo;
            }
            post.repostDate = orig.optLong("date", 0);
            post.repostText = orig.optString("text", "");
            post.repostOwnerId = orig.optLong("owner_id", fromId);
            post.repostPostId = orig.optLong("id", 0);
            JSONArray origAtt = orig.optJSONArray("attachments");
            if (origAtt != null) {
                for (int i = 0; i < origAtt.length() && post.repostPhotos.size() < 10; i++) {
                    JSONObject att = origAtt.getJSONObject(i);
                    if ("photo".equals(att.optString("type"))) {
                        ViewerPhoto photo = ViewerPhoto.parse(att.getJSONObject("photo"));
                        String u = photo.url.isEmpty() ? null : photo.url;
                        if (u != null) {
                            post.repostPhotos.add(u);
                            post.repostPhotoDetails.add(photo);
                        }
                    }
                }
            }
        }
        return post;
    }

    private Post.Card parseCard(String type, JSONObject o) {
        if (o == null) {
            return null;
        }
        Post.Card card = new Post.Card();
        card.type = type;
        try {
            switch (type) {
                case "poll": {
                    card.title = o.optString("question", "");
                    card.pollId = o.optLong("id", 0);
                    card.pollOwnerId = o.optLong("owner_id", 0);
                    card.pollAnonymous = o.optInt("anonymous", 0) == 1;
                    card.pollMultiple = o.optInt("multiple", 0) == 1;
                    long myAnswer = -1;
                    JSONObject answerJson = o.optJSONObject("answer");
                    if (answerJson != null && answerJson.optInt("id", -1) > 0) {
                        myAnswer = answerJson.getLong("id");
                        card.pollVoted = true;
                    }
                    card.pollAnswerId = myAnswer;
                    JSONArray answers = o.optJSONArray("answers");
                    int votedTotal = 0;
                    if (answers != null) {
                        for (int i = 0; i < answers.length(); i++) {
                            JSONObject a = answers.getJSONObject(i);
                            Post.PollOption option = new Post.PollOption(
                                    a.getLong("id"), a.optString("text", ""),
                                    a.optDouble("rate", 0), a.optInt("votes", 0));
                            votedTotal += option.votes;
                            card.options.add(option);
                        }
                    }
                    card.pollTotal = votedTotal;
                    return card;
                }
                case "audio":
                case "audio_message": {
                    if ("audio".equals(type)) {
                        card.title = o.optString("title", "");
                        card.subtitle = o.optString("artist", "");
                        card.audioUrl = o.optString("url", "");
                        card.duration = o.optInt("duration", 0);
                    } else {
                        card.title = "Голосовое сообщение";
                        card.subtitle = "";
                        card.audioUrl = o.optString("link_mp3", "");
                        card.duration = o.optInt("duration", 0);
                    }
                    return card;
                }
                case "link": {
                    card.title = o.optString("title", "");
                    card.subtitle = o.optString("caption", o.optString("description", ""));
                    card.linkUrl = o.optString("url", "");
                    JSONObject photo = o.optJSONObject("photo");
                    if (photo != null) {
                        card.imagePhoto = ViewerPhoto.parse(photo);
                        card.imageUrl = card.imagePhoto.url;
                    } else if (o.has("photo_130")) {
                        card.imageUrl = o.optString("photo_130");
                    }
                    return card;
                }
                case "article": {
                    card.title = o.optString("title", "");
                    int articleViews = o.optInt("views", 0);
                    card.subtitle = articleViews > 0
                            ? FeedAdapter.formatCount(articleViews) + " просмотров" : "";
                    card.linkUrl = o.optString("url", "");
                    JSONObject photo = o.optJSONObject("photo");
                    if (photo == null) {
                        photo = o.optJSONObject("cover_photo");
                    }
                    if (photo != null) {
                        card.imagePhoto = ViewerPhoto.parse(photo);
                        card.imageUrl = card.imagePhoto.url;
                    }
                    return card;
                }
                case "doc": {
                    card.title = o.optString("title", "");
                    card.subtitle = o.optInt("size", 0) / 1024 + " КБ";
                    card.linkUrl = o.optString("url", "");
                    return card;
                }
                case "geo": {
                    JSONObject place = o.optJSONObject("place");
                    card.title = place != null ? place.optString("title", "") : "";
                    return card;
                }
                case "sticker": {
                    card.imageUrl = o.optString("photo_256", o.optString("photo_128", ""));
                    return card;
                }
                case "album": {
                    card.title = o.optString("title", "");
                    card.linkUrl = "https://m.vk.com/album" + o.optLong("owner_id") + "_" + o.optLong("id");
                    JSONArray thumbs = o.optJSONArray("thumbs");
                    if (thumbs != null) {
                        for (int i = 0; i < thumbs.length() && i < 3; i++) {
                            JSONObject t = thumbs.getJSONObject(i);
                            ViewerPhoto photo = ViewerPhoto.parse(t);
                            if (!photo.url.isEmpty()) {
                                card.photos.add(photo.url);
                                card.photoDetails.add(photo);
                            }
                        }
                    }
                    return card;
                }
                case "note": {
                    card.title = o.optString("title", "");
                    card.linkUrl = "https://m.vk.com/note" + o.optLong("owner_id") + "_" + o.optLong("id");
                    return card;
                }
                case "market": {
                    card.title = o.optString("title", "");
                    card.subtitle = o.optString("price", "");
                    JSONObject photo = o.optJSONObject("photo");
                    if (photo != null) {
                        card.imagePhoto = ViewerPhoto.parse(photo);
                        card.imageUrl = card.imagePhoto.url;
                    }
                    card.linkUrl = "https://m.vk.com/market";
                    return card;
                }
                case "gift":
                case "graffiti": {
                    card.imageUrl = o.optString("photo_256", o.optString("url", ""));
                    return card;
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String pickPhotoUrl(JSONObject photo) {
        String url = PostPhoto.bestUrl(photo);
        return url.isEmpty() ? null : url;
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
