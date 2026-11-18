package com.vktea.photos;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел фотографий: «Все фото» (photos.getAll) и «Альбомы» (photos.getAlbums -> photos.get),
 * как табовый PhotosFragment Coffee. Только чтение; действия выполняются в просмотрщике.
 */
public class PhotosFragment extends Fragment {

    private static final int PAGE_SIZE = 60;

    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private View stateView, loadingView, retry;
    private TextView stateText;
    private PhotosAdapter adapter;
    private boolean loadingData;
    private boolean albumsTab;
    private int nextOffset;
    private boolean hasMore = true;
    @Nullable private PhotosModels.AlbumEntry openAlbum;
    private long sessionGeneration = -1;
    private int requestGeneration;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_photos, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionGeneration = Session.generation();
        list = view.findViewById(R.id.photos_list);
        refresh = view.findViewById(R.id.photos_refresh);
        stateView = view.findViewById(R.id.photos_state);
        stateText = view.findViewById(R.id.photos_state_text);
        loadingView = view.findViewById(R.id.photos_loading);
        retry = view.findViewById(R.id.photos_retry);
        adapter = new PhotosAdapter(new PhotosAdapter.Listener() {
            @Override public void openPhoto(int index) { PhotosFragment.this.openViewer(index); }
            @Override public void openAlbum(PhotosModels.AlbumEntry album) {
                PhotosFragment.this.openAlbum = album;
                nextOffset = 0;
                hasMore = true;
                load(true);
            }
        });
        GridLayoutManager grid = new GridLayoutManager(requireContext(), 3);
        list.setLayoutManager(grid);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || albumsTab || openAlbum != null && adapter.isAlbumMode()) return;
                int last = grid.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 6 && hasMore && !loadingData) load(false);
            }
        });
        refresh.setOnRefreshListener(this::reload);
        retry.setOnClickListener(v -> reload());
        view.<com.google.android.material.tabs.TabLayout>findViewById(R.id.photos_tabs)
                .addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                    @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                        albumsTab = tab.getPosition() == 1;
                        openAlbum = null;
                        reload();
                    }
                    @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
                    @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
                });
        reload();
    }

    private void reload() {
        nextOffset = 0;
        hasMore = true;
        load(true);
    }

    private void openViewer(int index) {
        List<PhotosModels.PhotoEntry> photos = adapter.currentPhotos();
        ArrayList<String> urls = new ArrayList<>(photos.size());
        ArrayList<com.vktea.feed.ViewerPhoto> metas = new ArrayList<>(photos.size());
        for (PhotosModels.PhotoEntry photo : photos) {
            urls.add(photo.url);
            metas.add(photo.meta);
        }
        com.vktea.feed.PostPhotosRenderer.openViewer(requireContext(), urls, metas, index);
    }

    private void load(boolean restart) {
        if (loadingData || !Session.isAuthorized(requireContext())) return;
        if (restart && sessionGeneration != Session.generation()) {
            sessionGeneration = Session.generation();
        }
        loadingData = true;
        final int generation = ++requestGeneration;
        if (restart) loadingView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        boolean wantAlbums = openAlbum == null && albumsTab;
        String method;
        String params;
        if (openAlbum != null) {
            method = "photos.get";
            params = "owner_id=" + Session.getUserId(requireContext())
                    + "&album_id=" + openAlbum.id
                    + "&count=" + PAGE_SIZE + "&offset=" + nextOffset
                    + "&extended=1&photo_sizes=1";
        } else if (wantAlbums) {
            method = "photos.getAlbums";
            params = "owner_id=" + Session.getUserId(requireContext())
                    + "&need_covers=1&photo_sizes=1";
        } else {
            method = "photos.getAll";
            params = "owner_id=" + Session.getUserId(requireContext())
                    + "&extended=1&photo_sizes=1&count=" + PAGE_SIZE + "&offset=" + nextOffset;
        }
        VKApi.get(requireContext(), method, params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (generation != requestGeneration) return;
                JSONObject data = response == null ? null : response.optJSONObject("response");
                JSONArray items = data == null ? null : data.optJSONArray("items");
                int total = data == null ? 0 : data.optInt("count", 0);
                List<PhotosModels.PhotoEntry> photos =
                        openAlbum != null || !albumsTab ? PhotosModels.parsePhotos(items)
                                : java.util.Collections.emptyList();
                List<PhotosModels.AlbumEntry> albums = PhotosModels.parseAlbums(items);
                requireActivity().runOnUiThread(() -> {
                    loadingData = false;
                    loadingView.setVisibility(View.GONE);
                    refresh.setRefreshing(false);
                    if (openAlbum == null && albumsTab) {
                        adapter.showAlbums(albums);
                        stateText.setText(R.string.photos_empty_albums);
                        stateView.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                        return;
                    }
                    adapter.showPhotos(photos, nextOffset > 0);
                    nextOffset += photos.size();
                    hasMore = total <= 0 || nextOffset < total;
                    if (photos.isEmpty() && adapter.photoCount() == 0) {
                        stateText.setText(R.string.photos_empty_all);
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
                    if (adapter.getItemCount() == 0) {
                        stateText.setText(R.string.photos_load_failed);
                        retry.setVisibility(View.VISIBLE);
                        stateView.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(requireContext(), R.string.photos_load_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
