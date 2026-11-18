package com.vktea.fave;

import android.content.Intent;
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

import com.bumptech.glide.Glide;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.feed.PostActivity;
import com.vktea.feed.PostPhotosRenderer;
import com.vktea.feed.VideoPlayerActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Закладки: единый fave.get; открытие постов/фото/видео нативными экранами. */
public class FaveFragment extends Fragment {

    private static final int PAGE_SIZE = 30;

    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private View stateView, loadingView, retry;
    private TextView stateText;
    private final List<FaveModels.FaveEntry> entries = new ArrayList<>();
    private boolean loadingData;
    private int nextOffset;
    private boolean hasMore = true;
    private int requestGeneration;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_fave, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.fave_list);
        refresh = view.findViewById(R.id.fave_refresh);
        stateView = view.findViewById(R.id.fave_state);
        stateText = view.findViewById(R.id.fave_state_text);
        loadingView = view.findViewById(R.id.fave_loading);
        retry = view.findViewById(R.id.fave_retry);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        list.setLayoutManager(layout);
        list.setAdapter(new Adapter());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                if (layout.findLastVisibleItemPosition() >= entries.size() - 4
                        && hasMore && !loadingData) load(false);
            }
        });
        refresh.setOnRefreshListener(this::reload);
        retry.setOnClickListener(v -> reload());
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
        if (restart) loadingView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        VKApi.get(requireContext(), "fave.get",
                "count=" + PAGE_SIZE + "&offset=" + nextOffset
                        + "&fields=first_name,last_name,photo_100,name",
                new VKApi.Callback() {
                    @Override public void success(JSONObject response) {
                        if (generation != requestGeneration) return;
                        Map<Long, String> names = new HashMap<>();
                        List<FaveModels.FaveEntry> page = FaveModels.parse(response, names);
                        JSONObject data = response == null ? null : response.optJSONObject("response");
                        int total = data == null ? 0 : data.optInt("count", 0);
                        requireActivity().runOnUiThread(() -> {
                            loadingData = false;
                            loadingView.setVisibility(View.GONE);
                            refresh.setRefreshing(false);
                            if (nextOffset == 0) entries.clear();
                            entries.addAll(page);
                            nextOffset += page.size();
                            hasMore = total <= 0 || nextOffset < total;
                            list.getAdapter().notifyDataSetChanged();
                            if (entries.isEmpty()) {
                                stateText.setText(R.string.fave_empty);
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
                            if (entries.isEmpty()) {
                                stateText.setText(R.string.fave_load_failed);
                                retry.setVisibility(View.VISIBLE);
                                stateView.setVisibility(View.VISIBLE);
                            } else {
                                Toast.makeText(requireContext(), R.string.fave_load_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    private void open(FaveModels.FaveEntry entry) {
        switch (entry.kind) {
            case POST:
                if (entry.ownerId != 0 && entry.itemId != 0) {
                    PostActivity.startById(requireContext(), entry.ownerId, entry.itemId);
                }
                break;
            case PHOTO: {
                ArrayList<String> urls = new ArrayList<>();
                urls.add(entry.previewUrl);
                PostPhotosRenderer.openViewer(requireContext(), urls,
                        new ArrayList<>(), 0);
                break;
            }
            case VIDEO:
                if (entry.ownerId != 0 && entry.itemId != 0) {
                    startActivity(new Intent(requireContext(), VideoPlayerActivity.class)
                            .putExtra("owner_id", entry.ownerId)
                            .putExtra("video_id", entry.itemId));
                }
                break;
            case LINK:
                if (!entry.subtitle.isEmpty()) {
                    try {
                        startActivity(new Intent(requireContext(), com.vktea.auth.AuthWebViewActivity.class)
                                .setData(android.net.Uri.parse(entry.subtitle)));
                    } catch (Exception ignored) {
                        Toast.makeText(requireContext(), R.string.chat_attachment_open_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            case USER:
            case GROUP:
                if (entry.kind == FaveModels.Kind.USER) {
                    startActivity(new Intent(requireContext(), MainActivity.class)
                            .putExtra(MainActivity.EXTRA_OPEN_PROFILE, entry.ownerId));
                }
                break;
            default:
                break;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_fave_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            FaveModels.FaveEntry entry = entries.get(position);
            holder.title.setText(entry.title);
            holder.subtitle.setText(entry.subtitle);
            if (entry.previewUrl.isEmpty()) {
                holder.preview.setImageResource(typeIcon(entry.kind));
            } else {
                Glide.with(holder.preview).load(entry.previewUrl).centerCrop().into(holder.preview);
            }
            holder.itemView.setOnClickListener(v -> open(entry));
        }

        @Override public int getItemCount() { return entries.size(); }

        @Override public void onViewRecycled(@NonNull Holder holder) {
            Glide.with(holder.preview).clear(holder.preview);
            super.onViewRecycled(holder);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView preview;
            final TextView title, subtitle;
            Holder(@NonNull View itemView) {
                super(itemView);
                preview = itemView.findViewById(R.id.fave_preview);
                title = itemView.findViewById(R.id.fave_title);
                subtitle = itemView.findViewById(R.id.fave_subtitle);
            }
        }
    }

    private static int typeIcon(FaveModels.Kind kind) {
        switch (kind) {
            case POST: return R.drawable.ic_menu_news;
            case PHOTO: return R.drawable.ic_menu_photos;
            case VIDEO: return R.drawable.ic_menu_videos;
            case LINK: return R.drawable.ic_favorites;
            default: return R.drawable.ic_favorites;
        }
    }
}
