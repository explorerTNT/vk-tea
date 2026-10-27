package com.vktea.photos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.FileProvider;

import java.io.File;
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

/** Album browser and explicitly confirmed photo management. */
public class PhotosFragment extends Fragment {
    private static final String OWNER = "photo_owner", ALBUM = "photo_album", TITLE = "photo_title", DIRECT = "photo_direct", KIND = "photo_kind";
    private final ArrayList<PhotoCatalog.Album> albums = new ArrayList<>();
    private final ArrayList<ViewerPhoto> photos = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Set<String> knownPhotoDescriptions = new HashSet<>();
    private PhotoCatalog catalog;
    private PhotoWrites writes;
    private long pendingAlbum;
    private String pendingUser;
    private Button manage;
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::photoPicked);
    private long owner;
    private Long album;
    private PhotoCatalog.Kind kind = PhotoCatalog.Kind.ALBUM;
    private String title = "";
    private boolean direct, loading, loaded, interruptedWrite;
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
        interruptedWrite = state != null && state.getBoolean("photo_write_pending", false);
        pendingAlbum = state == null ? 0 : state.getLong("photo_picker_album", 0);
        pendingUser = state == null ? null : state.getString("photo_picker_user");
        catalog = new PhotoCatalog(requireContext());
        back = new OnBackPressedCallback(false) { @Override public void handleOnBackPressed() { showAlbums(); } };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, back);
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        LinearLayout root = new LinearLayout(requireContext()); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(requireContext().getColor(R.color.menu_bg));
        heading = new TextView(requireContext()); heading.setTextSize(18); heading.setTextColor(requireContext().getColor(R.color.almost_black)); heading.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(heading);
        manage = new Button(requireContext()); manage.setAllCaps(false); manage.setOnClickListener(v -> { if (album == null) editAlbumDialog(null); else { pendingAlbum = album; pendingUser = Session.getUserId(requireContext()); picker.launch(new String[]{"image/jpeg", "image/png", "image/gif"}); } }); root.addView(manage);
        status = new TextView(requireContext()); status.setGravity(Gravity.CENTER); status.setTextColor(requireContext().getColor(R.color.section_header)); status.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(status);
        refresh = new SwipeRefreshLayout(requireContext());
        grid = new RecyclerView(requireContext()); layout = new GridLayoutManager(requireContext(), album == null ? 2 : 3); grid.setLayoutManager(layout);
        adapter = new Adapter(); grid.setAdapter(adapter); refresh.addView(grid);
        root.addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
        more = new Button(requireContext()); more.setAllCaps(false); more.setOnClickListener(v -> load()); root.addView(more);
        refresh.setOnRefreshListener(this::reload);
        return root;
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        if (interruptedWrite) {
            interruptedWrite = false;
            Toast.makeText(requireContext(), "Операция прервана. Проверьте изменения в VK перед повтором", Toast.LENGTH_LONG).show();
        }
        reload();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void reload() {
        cancel(); albums.clear(); photos.clear(); identities.clear(); knownPhotoDescriptions.clear(); offset = total = 0; loaded = false;
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
                for (ViewerPhoto item : page.photos) {
                    String key = item.ownerId + ":" + item.id;
                    if (identities.add(item.hasIdentity() ? "p" + key : "u" + item.url)) {
                        photos.add(item);
                        if (page.knownPhotoDescriptions.contains(key)) knownPhotoDescriptions.add(key);
                    }
                }
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
        manage.setVisibility(canManage() && (album == null || kind == PhotoCatalog.Kind.ALBUM && album > 0) ? View.VISIBLE : View.GONE);
        manage.setText(album == null ? "Создать альбом · удерживайте альбом для редактирования" : "Загрузить фото · удерживайте фото для описания");
        manage.setEnabled(writes == null);
        back.setEnabled(album != null && !direct);
        heading.setOnClickListener(album != null && !direct ? v -> showAlbums() : null);
        heading.setContentDescription(album != null && !direct ? "Назад к фотоальбомам: " + heading.getText() : heading.getText());
        status.setVisibility(error != null || empty ? View.VISIBLE : View.GONE);
        status.setText(error != null ? error : loading && !loaded ? "Загрузка…" : album == null ? "Альбомов пока нет" : "В альбоме пока нет фотографий");
        refresh.setRefreshing(loading && loaded);
        more.setVisibility(error != null || loaded && offset < total ? View.VISIBLE : View.GONE);
        more.setEnabled(!loading); more.setText(error != null ? "Повторить" : "Загрузить ещё");
    }
    private boolean canManage() {
        if (owner < 0) return true;
        try { return owner > 0 && owner == Long.parseLong(Session.getUserId(requireContext())); }
        catch (RuntimeException ex) { return false; }
    }
    private void photoPicked(@Nullable Uri uri) {
        long target = pendingAlbum; pendingAlbum = 0;
        String pickerUser = pendingUser; pendingUser = null;
        if (uri == null || target <= 0 || album == null || target != album || !canManage()) return;
        android.content.Context application = requireContext().getApplicationContext();
        String account = Session.getUserId(requireContext());
        if (account == null || account.isEmpty() || !account.equals(pickerUser)) return;
        PhotoEditor.show(this, uri, (rotation, crop) -> {
            if (!isAdded() || getView() == null || album == null || target != album || !account.equals(Session.getUserId(requireContext()))) return;
            perform(op -> {
                File edited = null;
                try {
                    Uri source = uri;
                    if (rotation != 0 || crop != null) {
                        edited = PhotoEditor.render(application, uri, rotation, crop);
                        source = FileProvider.getUriForFile(application, application.getPackageName() + ".photo-files", edited);
                    }
                    op.upload(owner, target, source);
                } finally { if (edited != null) edited.delete(); }
            });
        });
    }
    interface WriteAction { void run(PhotoWrites op) throws Exception; }
    private void perform(WriteAction action) {
        if (writes != null || !canManage()) return;
        PhotoWrites op = new PhotoWrites(requireContext()); writes = op; render(null);
        op.start(() -> action.run(op), (message, success) -> {
            if (writes != op || getView() == null) return;
            writes = null; Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            if (success) reload(); else render(null);
        });
    }
    private void editAlbumDialog(@Nullable PhotoCatalog.Album existing) {
        if (!canManage()) return;
        LinearLayout fields = new LinearLayout(requireContext()); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(requireContext()); name.setHint("Название"); name.setSingleLine(true);
        EditText description = new EditText(requireContext()); description.setHint("Описание");
        if (existing != null) {
            if (existing.titleKnown) name.setText(existing.title);
            else name.setHint("Название не получено из VK — введите новое");
            if (existing.description == null) description.setHint("Описание (оставьте пустым, чтобы не менять)");
            else description.setText(existing.description);
        }
        fields.addView(name); fields.addView(description);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(existing == null ? "Создать альбом" : "Редактировать альбом")
                .setView(fields).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim(), entered = description.getText().toString();
            String desc = existing != null && existing.description == null && entered.isEmpty() ? null : entered;
            if (value.isEmpty()) { name.setError("Укажите название"); return; }
            dialog.dismiss(); perform(op -> { if (existing == null) op.createAlbum(owner, value, desc); else op.editAlbum(owner, existing.id, value, desc); });
        })); dialog.show();
    }
    private void editPhotoDialog(ViewerPhoto photo) {
        if (!canManage() || !photo.hasIdentity() || photo.ownerId != owner) return;
        boolean known = knownPhotoDescriptions.contains(photo.ownerId + ":" + photo.id);
        EditText caption = new EditText(requireContext());
        caption.setHint(known ? "Описание фотографии" : "Описание недоступно — введите новое");
        if (known) caption.setText(photo.text);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle("Описание фотографии").setView(caption)
                .setMessage(known ? null : "VK не вернул текущее описание. Введите новое; пустое значение удалит прежнее описание.")
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (d, w) -> {
                    String value = caption.getText().toString(); perform(op -> op.editPhoto(owner, photo.id, value));
                }).create();
        if (!known) dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            caption.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                @Override public void afterTextChanged(android.text.Editable text) { }
            });
        });
        dialog.show();
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
        out.putBoolean("photo_write_pending", writes != null || interruptedWrite);
        out.putLong("photo_picker_album", pendingAlbum);
        out.putString("photo_picker_user", pendingUser);
        if (album != null) { out.putLong(ALBUM, album); out.putString(TITLE, title); out.putString(KIND, kind.name()); }
        super.onSaveInstanceState(out);
    }
    @Override public void onDestroyView() {
        cancel(); if (writes != null) { interruptedWrite = true; writes.cancel(); writes = null; } if (grid != null) grid.setAdapter(null); adapter = null; grid = null; layout = null;
        heading = null; status = null; more = null; manage = null; refresh = null; super.onDestroyView();
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
                holder.itemView.setContentDescription(item.title + ", фотографий: " + item.size + (canManage() && item.id > 0 && item.kind == PhotoCatalog.Kind.ALBUM ? ". Удерживайте для редактирования" : ""));
                holder.itemView.setOnClickListener(v -> openAlbum(item));
                holder.itemView.setOnLongClickListener(canManage() && item.kind == PhotoCatalog.Kind.ALBUM && item.id > 0 ? v -> { editAlbumDialog(item); return true; } : null);
            } else {
                ViewerPhoto item = photos.get(position); url = item.url;
                holder.caption.setVisibility(View.GONE); holder.caption.setText("");
                holder.itemView.setContentDescription("Открыть фотографию " + (position + 1) + (canManage() && item.hasIdentity() && item.ownerId == owner ? ". Удерживайте для редактирования описания" : ""));
                holder.itemView.setOnLongClickListener(canManage() && item.hasIdentity() && item.ownerId == owner ? v -> { editPhotoDialog(item); return true; } : null);
                holder.itemView.setOnClickListener(v -> { int index = holder.getBindingAdapterPosition(); if (index != RecyclerView.NO_POSITION) openPhoto(index); });
            }
            Glide.with(holder.image).clear(holder.image);
            if (url.startsWith("https://") || url.startsWith("http://")) Glide.with(holder.image).load(url).centerCrop().into(holder.image);
        }
        @Override public int getItemCount() { return album == null ? albums.size() : photos.size(); }
        @Override public void onViewRecycled(@NonNull Holder holder) { Glide.with(holder.image).clear(holder.image); holder.itemView.setOnClickListener(null); holder.itemView.setOnLongClickListener(null); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image; final TextView caption;
        Holder(View view, ImageView image, TextView caption) { super(view); this.image = image; this.caption = caption; }
    }
}
