package com.vktea.photos;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.vktea.R;
import com.vktea.auth.Session;
import com.vktea.feed.PhotoViewerActivity;
import com.vktea.feed.ViewerPhoto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;

/** Read-only album browser, including system albums and explicit page loading. */
public class PhotosFragment extends Fragment {
    private static final String OWNER = "photo_owner", ALBUM = "photo_album", TITLE = "photo_title", DIRECT = "photo_direct", KIND = "photo_kind";
    private final ArrayList<PhotoCatalog.Album> albums = new ArrayList<>();
    private final ArrayList<ViewerPhoto> photos = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private PhotoCatalog catalog;
    private long owner;
    private Long album;
    private PhotoCatalog.Kind kind = PhotoCatalog.Kind.ALBUM;
    private String title = "";
    private boolean direct, loading, loaded;
    private int offset, total, generation;
    private Call request;
    private TextView heading, status;
    private Button more;
    private SwipeRefreshLayout refresh;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private Adapter adapter;
    private OnBackPressedCallback back;

    public PhotosFragment() { }
    public static PhotosFragment newInstance(long ownerId) {
        PhotosFragment fragment = new PhotosFragment(); Bundle args = new Bundle(); args.putLong(OWNER, ownerId); fragment.setArguments(args); return fragment;
    }
    public static PhotosFragment newAlbumInstance(long ownerId, long albumId, String title) {
        PhotosFragment fragment = newInstance(ownerId); Bundle args = fragment.requireArguments();
        args.putLong(ALBUM, albumId); args.putString(TITLE, title); args.putBoolean(DIRECT, true); return fragment;
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Bundle args = getArguments();
        owner = args == null ? 0 : args.getLong(OWNER, 0);
        if (owner == 0) try { owner = Long.parseLong(Session.getUserId(requireContext())); } catch (RuntimeException ignored) { owner = 0; }
        Bundle source = state != null ? state : args;
        if (source != null && source.containsKey(ALBUM)) { album = source.getLong(ALBUM); title = source.getString(TITLE, ""); }
        if (source != null) try { kind = PhotoCatalog.Kind.valueOf(source.getString(KIND, PhotoCatalog.Kind.ALBUM.name())); } catch (IllegalArgumentException ignored) { kind = PhotoCatalog.Kind.ALBUM; }
        direct = args != null && args.getBoolean(DIRECT, false);
        catalog = new PhotoCatalog(requireContext());
        back = new OnBackPressedCallback(false) { @Override public void handleOnBackPressed() { showAlbums(); } };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, back);
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        LinearLayout root = new LinearLayout(requireContext()); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(requireContext().getColor(R.color.menu_bg));
        heading = new TextView(requireContext()); heading.setTextSize(18); heading.setTextColor(requireContext().getColor(R.color.almost_black)); heading.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(heading);
        status = new TextView(requireContext()); status.setGravity(Gravity.CENTER); status.setTextColor(requireContext().getColor(R.color.section_header)); status.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(status);
        refresh = new SwipeRefreshLayout(requireContext());
        grid = new RecyclerView(requireContext()); layout = new GridLayoutManager(requireContext(), album == null ? 2 : 3); grid.setLayoutManager(layout);
        adapter = new Adapter(); grid.setAdapter(adapter); refresh.addView(grid);
        root.addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
        more = new Button(requireContext()); more.setAllCaps(false); more.setOnClickListener(v -> load()); root.addView(more);
        refresh.setOnRefreshListener(this::reload);
        return root;
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) { reload(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void reload() {
        cancel(); albums.clear(); photos.clear(); identities.clear(); offset = total = 0; loaded = false;
        if (adapter != null) { layout.setSpanCount(album == null ? 2 : 3); adapter.notifyDataSetChanged(); grid.scrollToPosition(0); }
        load();
    }
    private void cancel() { generation++; if (request != null) request.cancel(); request = null; loading = false; }
    private void load() {
        if (loading || loaded && offset >= total) return;
        loading = true; int current = ++generation; render(null);
        request = catalog.load(owner, album, kind, offset, new PhotoCatalog.Callback() {
            @Override public void success(PhotoCatalog.Page page) {
                if (current != generation || getView() == null) return;
                request = null; loading = false; loaded = true; total = page.total; offset += page.consumed;
                for (PhotoCatalog.Album item : page.albums) if (identities.add(item.identity())) albums.add(item);
                for (ViewerPhoto item : page.photos) if (identities.add(item.hasIdentity() ? "p" + item.ownerId + ":" + item.id : "u" + item.url)) photos.add(item);
                adapter.notifyDataSetChanged(); render(null);
            }
            @Override public void error(String message) {
                if (current != generation || getView() == null) return;
                request = null; loading = false; render(message);
            }
        });
    }
    private void render(@Nullable String error) {
        if (heading == null) return;
        boolean empty = album == null ? albums.isEmpty() : photos.isEmpty();
        heading.setText(album == null ? "Фотоальбомы" : title.isEmpty() ? "Фотографии" : title);
        back.setEnabled(album != null && !direct);
        heading.setOnClickListener(album != null && !direct ? v -> showAlbums() : null);
        heading.setContentDescription(album != null && !direct ? "Назад к фотоальбомам: " + heading.getText() : heading.getText());
        status.setVisibility(error != null || empty ? View.VISIBLE : View.GONE);
        status.setText(error != null ? error : loading && !loaded ? "Загрузка…" : album == null ? "Альбомов пока нет" : "В альбоме пока нет фотографий");
        refresh.setRefreshing(loading && loaded);
        more.setVisibility(error != null || loaded && offset < total ? View.VISIBLE : View.GONE);
        more.setEnabled(!loading); more.setText(error != null ? "Повторить" : "Загрузить ещё");
    }
    private void showAlbums() { album = null; kind = PhotoCatalog.Kind.ALBUM; title = ""; reload(); }
    private void openAlbum(PhotoCatalog.Album selected) { album = selected.id; kind = selected.kind; title = selected.title; reload(); }
    private void openPhoto(int position) {
        if (position < 0 || position >= photos.size()) return;
        // Keep the intent comfortably bounded even for very large albums.
        int start = Math.max(0, position - 50), end = Math.min(photos.size(), start + 100);
        ArrayList<ViewerPhoto> window = new ArrayList<>(photos.subList(start, end));
        Intent intent = new Intent(requireContext(), PhotoViewerActivity.class);
        intent.putParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS, window);
        intent.putExtra(PhotoViewerActivity.EXTRA_INDEX, position - start); startActivity(intent);
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        if (album != null) { out.putLong(ALBUM, album); out.putString(TITLE, title); out.putString(KIND, kind.name()); }
        super.onSaveInstanceState(out);
    }
    @Override public void onDestroyView() {
        cancel(); if (grid != null) grid.setAdapter(null); adapter = null; grid = null; layout = null;
        heading = null; status = null; more = null; refresh = null; super.onDestroyView();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout cell = new LinearLayout(parent.getContext()); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(dp(2), dp(2), dp(2), dp(2));
            ImageView image = new ImageView(parent.getContext()); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackgroundColor(parent.getContext().getColor(R.color.menu_selected_bg)); cell.addView(image, new LinearLayout.LayoutParams(-1, dp(120)));
            TextView caption = new TextView(parent.getContext()); caption.setTextColor(parent.getContext().getColor(R.color.almost_black)); caption.setPadding(dp(6), dp(4), dp(6), dp(8)); caption.setMaxLines(2); cell.addView(caption);
            return new Holder(cell, image, caption);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            boolean isAlbum = album == null;
            String url;
            if (isAlbum) {
                PhotoCatalog.Album item = albums.get(position); url = item.cover;
                holder.caption.setVisibility(View.VISIBLE); holder.caption.setText(item.title + " · " + item.size);
                holder.itemView.setContentDescription(item.title + ", фотографий: " + item.size);
                holder.itemView.setOnClickListener(v -> openAlbum(item));
            } else {
                ViewerPhoto item = photos.get(position); url = item.url;
                holder.caption.setVisibility(View.GONE); holder.caption.setText("");
                holder.itemView.setContentDescription("Открыть фотографию " + (position + 1));
                holder.itemView.setOnClickListener(v -> { int index = holder.getBindingAdapterPosition(); if (index != RecyclerView.NO_POSITION) openPhoto(index); });
            }
            Glide.with(holder.image).clear(holder.image);
            if (url.startsWith("https://") || url.startsWith("http://")) Glide.with(holder.image).load(url).centerCrop().into(holder.image);
        }
        @Override public int getItemCount() { return album == null ? albums.size() : photos.size(); }
        @Override public void onViewRecycled(@NonNull Holder holder) { Glide.with(holder.image).clear(holder.image); holder.itemView.setOnClickListener(null); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image; final TextView caption;
        Holder(View view, ImageView image, TextView caption) { super(view); this.image = image; this.caption = caption; }
    }
}
