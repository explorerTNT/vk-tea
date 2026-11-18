package com.vktea.videos;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.feed.VideoPlayerActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел видео: список своих видео (video.get) и поиск (video.search).
 * Воспроизведение открывает существующий Media3-плеер.
 */
public class VideosFragment extends Fragment {

    private static final int PAGE_SIZE = 30;

    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private View stateView, loadingView, retry;
    private TextView stateText;
    private EditText search;
    private final List<VideoModels.VideoEntry> videos = new ArrayList<>();
    private String query = "";
    private boolean loadingData;
    private int nextOffset;
    private boolean hasMore = true;
    private int requestGeneration;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_videos, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.videos_list);
        refresh = view.findViewById(R.id.videos_refresh);
        stateView = view.findViewById(R.id.videos_state);
        stateText = view.findViewById(R.id.videos_state_text);
        loadingView = view.findViewById(R.id.videos_loading);
        retry = view.findViewById(R.id.videos_retry);
        search = view.findViewById(R.id.videos_search);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        list.setLayoutManager(layout);
        list.setAdapter(new Adapter());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                if (layout.findLastVisibleItemPosition() >= videos.size() - 4
                        && hasMore && !loadingData) load(false);
            }
        });
        refresh.setOnRefreshListener(this::reload);
        retry.setOnClickListener(v -> reload());
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                query = search.getText().toString().trim();
                reload();
                return true;
            }
            return false;
        });
        reload();
    }

    private void reload() {
        nextOffset = 0;
        hasMore = true;
        load(true);
    }

    private void load(boolean restart) {
        if (loadingData || !Session.isAuthorized(requireContext())) return;
        loadingData = true;
        final int generation = ++requestGeneration;
        if (restart) loadingView.setVisibility(videos.isEmpty() ? View.VISIBLE : View.GONE);
        String method;
        String params;
        if (query.isEmpty()) {
            method = "video.get";
            params = "owner_id=" + Session.getUserId(requireContext())
                    + "&extended=1&count=" + PAGE_SIZE + "&offset=" + nextOffset;
        } else {
            method = "video.search";
            params = "q=" + android.net.Uri.encode(query)
                    + "&extended=1&count=" + PAGE_SIZE + "&offset=" + nextOffset + "&hd=1";
        }
        VKApi.get(requireContext(), method, params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (generation != requestGeneration) return;
                JSONObject data = response == null ? null : response.optJSONObject("response");
                JSONArray items = data == null ? null : data.optJSONArray("items");
                int total = data == null ? 0 : data.optInt("count", 0);
                List<VideoModels.VideoEntry> page = VideoModels.parse(items);
                requireActivity().runOnUiThread(() -> {
                    loadingData = false;
                    loadingView.setVisibility(View.GONE);
                    refresh.setRefreshing(false);
                    if (nextOffset == 0) videos.clear();
                    videos.addAll(page);
                    nextOffset += page.size();
                    hasMore = total <= 0 || nextOffset < total;
                    list.getAdapter().notifyDataSetChanged();
                    if (videos.isEmpty()) {
                        stateText.setText(query.isEmpty() ? R.string.videos_empty : R.string.videos_search_empty);
                        stateView.setVisibility(View.VISIBLE);
                    } else {
                        stateView.setVisibility(View.GONE);
                    }
                });
            }

            @Override public void error(Exception e) {
                if (generation != requestGeneration) return;
                requireActivity().runOnUiThread(() -> {
                    loadingData = false;
                    loadingView.setVisibility(View.GONE);
                    refresh.setRefreshing(false);
                    if (videos.isEmpty()) {
                        stateText.setText(R.string.videos_load_failed);
                        retry.setVisibility(View.VISIBLE);
                        stateView.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(requireContext(), R.string.videos_load_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video_card, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoModels.VideoEntry video = videos.get(position);
            Glide.with(holder.preview).load(video.previewUrl).centerCrop().into(holder.preview);
            holder.title.setText(video.title);
            holder.duration.setText(VideoModels.duration(video.duration));
            String views = video.views > 0
                    ? getString(R.string.videos_views, com.vktea.feed.FeedAdapter.formatCount(video.views))
                    : "";
            holder.meta.setText(views);
            holder.itemView.setOnClickListener(v -> {
                if (video.id == 0 || video.ownerId == 0) return;
                startActivity(new android.content.Intent(requireContext(), VideoPlayerActivity.class)
                        .putExtra("owner_id", video.ownerId)
                        .putExtra("video_id", video.id)
                        .putExtra("access_key", video.accessKey));
            });
        }

        @Override public int getItemCount() { return videos.size(); }

        @Override public void onViewRecycled(@NonNull Holder holder) {
            Glide.with(holder.preview).clear(holder.preview);
            super.onViewRecycled(holder);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final android.widget.ImageView preview;
            final TextView title, duration, meta;
            Holder(@NonNull View itemView) {
                super(itemView);
                preview = itemView.findViewById(R.id.video_preview);
                title = itemView.findViewById(R.id.video_title);
                duration = itemView.findViewById(R.id.video_duration);
                meta = itemView.findViewById(R.id.video_meta);
            }
        }
    }
}
