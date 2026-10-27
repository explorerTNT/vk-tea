package com.vktea.videos;

import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.google.android.material.tabs.TabLayout;
import com.vktea.R;
import com.vktea.feed.VideoPlayerActivity;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Browses the selected owner's videos and albums, plus VK video search. */
public class VideosFragment extends Fragment {
    private static final String OWNER = "videos_owner";
    public static VideosFragment newInstance(long ownerId) {
        VideosFragment fragment = new VideosFragment(); Bundle args = new Bundle(); args.putLong(OWNER, ownerId); fragment.setArguments(args); return fragment;
    }
    private VideosViewModel model;
    private TabLayout tabs;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private EditText search;
    private TextView notice, empty, more, albumBack;
    private View noticeRow, emptyState, progress, searchRow;
    private Adapter adapter;
    private boolean rendering;
    private OnBackPressedCallback albumBackHandler;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        long owner = getArguments() == null ? 0 : getArguments().getLong(OWNER, 0);
        model = new ViewModelProvider(this, new VideosViewModel.Factory(requireContext(), owner)).get(VideosViewModel.class);
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_videos, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        albumBackHandler = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { model.backToAlbums(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), albumBackHandler);
        tabs = view.findViewById(R.id.videos_tabs); list = view.findViewById(R.id.videos_list);
        refresh = view.findViewById(R.id.videos_refresh); search = view.findViewById(R.id.videos_search_input);
        notice = view.findViewById(R.id.videos_notice); empty = view.findViewById(R.id.videos_empty_label);
        more = view.findViewById(R.id.videos_more); albumBack = view.findViewById(R.id.videos_album_back);
        noticeRow = view.findViewById(R.id.videos_notice_row); emptyState = view.findViewById(R.id.videos_empty_state);
        progress = view.findViewById(R.id.videos_progress); searchRow = view.findViewById(R.id.videos_search_row);
        tabs.addTab(tabs.newTab().setText(R.string.videos_my)); tabs.addTab(tabs.newTab().setText(R.string.videos_albums));
        tabs.addTab(tabs.newTab().setText(R.string.videos_search_tab));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { if (!rendering) { model.setMode(tab.getPosition()); list.scrollToPosition(0); } }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        adapter = new Adapter(this::open); list.setLayoutManager(new LinearLayoutManager(requireContext())); list.setAdapter(adapter);
        refresh.setColorSchemeResources(R.color.vk_blue); refresh.setOnRefreshListener(model::refresh);
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.videos_retry).setOnClickListener(v -> model.loadMore());
        more.setOnClickListener(v -> model.loadMore()); albumBack.setOnClickListener(v -> model.backToAlbums());
        view.findViewById(R.id.videos_search_button).setOnClickListener(v -> performSearch());
        search.setOnEditorActionListener((v, action, event) -> { performSearch(); return true; });
        model.state().observe(getViewLifecycleOwner(), this::render); model.ensureLoaded();
    }
    public void showVideosSearch() {
        if (search == null || !isAdded()) return;
        model.setMode(VideosViewModel.SEARCH); search.requestFocus();
        search.post(() -> { if (search != null && search.getWindowToken() != null) {
            InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }});
    }
    private void performSearch() {
        if (search == null) return;
        model.search(search.getText().toString()); list.scrollToPosition(0);
        InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
    private void open(VideoEntry row) {
        if (!isAdded()) return;
        if (row.album) { model.openAlbum(row); list.scrollToPosition(0); return; }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class).putExtra("owner_id", row.ownerId).putExtra("video_id", row.id);
        if (!row.accessKey.isEmpty()) intent.putExtra("access_key", row.accessKey);
        startActivity(intent);
    }
    private void render(VideosViewModel.State state) {
        if (adapter == null) return;
        rendering = true;
        tabs.selectTab(tabs.getTabAt(state.mode));
        searchRow.setVisibility(state.mode == VideosViewModel.SEARCH ? View.VISIBLE : View.GONE);
        if (!search.hasFocus() && !search.getText().toString().equals(state.query)) search.setText(state.query);
        albumBackHandler.setEnabled(state.album != 0);
        albumBack.setVisibility(state.album != 0 ? View.VISIBLE : View.GONE);
        albumBack.setText(getString(R.string.videos_all_albums) + "  /  " + state.albumTitle);
        adapter.submit(state.rows);
        refresh.setRefreshing(state.loading && state.loaded);
        emptyState.setVisibility(state.rows.isEmpty() ? View.VISIBLE : View.GONE);
        progress.setVisibility(state.loading && state.rows.isEmpty() ? View.VISIBLE : View.GONE);
        int text = state.loading ? R.string.videos_loading : state.mode == VideosViewModel.SEARCH && state.query.isEmpty() ? R.string.videos_search_prompt
                : state.mode == VideosViewModel.SEARCH ? R.string.videos_search_empty
                : state.mode == VideosViewModel.ALBUMS && state.album == 0 ? R.string.videos_albums_empty : R.string.videos_empty;
        empty.setText(state.error != null ? "" : getString(text));
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error));
        more.setVisibility(state.hasMore() && state.error == null ? View.VISIBLE : View.GONE);
        more.setEnabled(!state.loading);
        rendering = false;
    }
    private static int errorText(String error) {
        switch (error) {
            case "auth": return R.string.videos_error_auth;
            case "access": return R.string.videos_error_access;
            case "rate": return R.string.videos_error_rate;
            case "network": return R.string.videos_error_network;
            default: return R.string.videos_error_generic;
        }
    }
    @Override public void onDestroyView() {
        if (list != null) list.setAdapter(null);
        list = null; adapter = null; search = null; tabs = null; refresh = null; albumBackHandler = null;
        notice = empty = more = albumBack = null; noticeRow = emptyState = progress = searchRow = null;
        super.onDestroyView();
    }
    private interface Listener { void open(VideoEntry item); }
    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final Listener listener; private List<VideoEntry> items = Collections.emptyList();
        Adapter(Listener listener) { this.listener = listener; }
        void submit(List<VideoEntry> rows) { items = rows; notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_catalog, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            VideoEntry row = items.get(position); Context context = h.itemView.getContext();
            h.title.setText(row.title.isEmpty() ? context.getString(R.string.videos_untitled) : row.title);
            h.subtitle.setVisibility(row.album ? View.VISIBLE : View.GONE);
            h.subtitle.setText(row.album ? context.getString(R.string.videos_album_count, row.count) : "");
            h.play.setVisibility(row.album ? View.GONE : View.VISIBLE);
            h.duration.setVisibility(!row.album && row.duration > 0 ? View.VISIBLE : View.GONE);
            h.duration.setText(duration(row.duration));
            Glide.with(h.image).clear(h.image);
            if (!row.image.isEmpty()) Glide.with(h.image).load(row.image).centerCrop().into(h.image);
            h.itemView.setOnClickListener(v -> listener.open(row));
        }
        static String duration(int seconds) { return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
        @Override public void onViewRecycled(@NonNull Holder h) { Glide.with(h.image).clear(h.image); h.itemView.setOnClickListener(null); super.onViewRecycled(h); }
        @Override public int getItemCount() { return items.size(); }
        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView image, play; final TextView title, subtitle, duration;
            Holder(View view) { super(view); image = view.findViewById(R.id.videos_image); play = view.findViewById(R.id.videos_play); title = view.findViewById(R.id.videos_title); subtitle = view.findViewById(R.id.videos_subtitle); duration = view.findViewById(R.id.videos_duration); }
        }
    }
}
