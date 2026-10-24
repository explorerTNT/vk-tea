package com.vktea.feed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.vktea.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PhotoViewerActivity extends AppCompatActivity {
    public static final String EXTRA_PHOTOS = "photos", EXTRA_INDEX = "index";
    private static final String STATE_INDEX = "photo_index", STATE_CHROME = "photo_chrome",
            STATE_DOCUMENT = "photo_document";
    private static final int SAVE = 1, COPY = 2, SHARE = 3, RESET = 4;
    private final List<String> photos = new ArrayList<>();
    private ViewPager2 pager;
    private PhotoPagerAdapter adapter;
    private PhotoExportViewModel exports;
    private View top, bottom, previous, next, save, share, reset;
    private TextView counter;
    private boolean chromeVisible = true, zoomed;
    private PhotoPagerAdapter.LoadState[] loads;
    private PopupMenu menu;
    private String pendingDocument;

    private final ActivityResultLauncher<Intent> document = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                String staged = pendingDocument;
                pendingDocument = null;
                Uri target = result.getData() == null ? null : result.getData().getData();
                if (staged == null) return;
                if (result.getResultCode() == RESULT_OK && target != null) exports.copyDocument(staged, target);
                else deleteStaged(staged);
                updateControls();
            });

    private final ViewPager2.OnPageChangeCallback pages = new ViewPager2.OnPageChangeCallback() {
        private int last = -1;
        @Override public void onPageSelected(int position) {
            if (last >= 0 && last != position) adapter.resetZoom(last);
            last = position;
            zoomed = false;
            adapter.resetZoom(position);
            pager.setUserInputEnabled(true);
            if (menu != null) menu.dismiss();
            updateControls();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTheme(R.style.Theme_VKTea_PhotoViewer);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ArrayList<String> input = getIntent().getStringArrayListExtra(EXTRA_PHOTOS);
        int requested = Math.max(0, getIntent().getIntExtra(EXTRA_INDEX, 0)), filteredIndex = 0;
        if (input != null) for (int i = 0; i < input.size(); i++) {
            String source = input.get(i);
            if (source != null && !source.trim().isEmpty()) {
                if (i < requested) filteredIndex++;
                photos.add(source);
            }
        }
        if (photos.isEmpty()) { finish(); return; }
        int initial = Math.min(photos.size() - 1, state == null ? filteredIndex
                : Math.max(0, state.getInt(STATE_INDEX, filteredIndex)));
        chromeVisible = state == null || state.getBoolean(STATE_CHROME, true);
        pendingDocument = state == null ? null : state.getString(STATE_DOCUMENT);
        setContentView(R.layout.activity_photo_viewer);
        View root = findViewById(R.id.viewer_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        top = findViewById(R.id.viewer_top);
        bottom = findViewById(R.id.viewer_bottom);
        previous = findViewById(R.id.viewer_previous);
        next = findViewById(R.id.viewer_next);
        save = findViewById(R.id.viewer_save);
        share = findViewById(R.id.viewer_share);
        reset = findViewById(R.id.viewer_reset);
        counter = findViewById(R.id.viewer_counter);
        pager = findViewById(R.id.photo_pager);
        loads = new PhotoPagerAdapter.LoadState[photos.size()];
        exports = new ViewModelProvider(this).get(PhotoExportViewModel.class);
        adapter = createPhotoAdapter(photos, new PhotoPagerAdapter.Callback() {
            @Override public void onToggleChrome() { setChromeVisible(!chromeVisible); }
            @Override public void onZoomChanged(int position, boolean value) {
                if (position != pager.getCurrentItem()) return;
                zoomed = value;
                pager.setUserInputEnabled(!value);
                updateControls();
            }
            @Override public void onLoadStateChanged(int position, PhotoPagerAdapter.LoadState value) {
                loads[position] = value;
                updateControls();
            }
        });
        pager.setAdapter(adapter);
        pager.setCurrentItem(initial, false);
        pager.registerOnPageChangeCallback(pages);
        findViewById(R.id.viewer_back).setOnClickListener(view -> finish());
        findViewById(R.id.viewer_more).setOnClickListener(this::showActions);
        previous.setOnClickListener(view -> move(-1));
        next.setOnClickListener(view -> move(1));
        save.setOnClickListener(view -> onSavePhoto(currentSource()));
        share.setOnClickListener(view -> onSharePhoto(currentSource()));
        reset.setOnClickListener(view -> adapter.resetZoom(pager.getCurrentItem()));
        exports.state().observe(this, value -> {
            updateControls();
            if (value == null || value.busy || value.handled) return;
            value.handled = true;
            if (value.kind == PhotoExportViewModel.Kind.ERROR) toast(R.string.photo_action_error);
            else if (value.kind == PhotoExportViewModel.Kind.SAVED) toast(R.string.photo_action_saved);
            else if (value.kind == PhotoExportViewModel.Kind.SHARE) dispatchShare(value.photo);
            else if (value.kind == PhotoExportViewModel.Kind.DOCUMENT) chooseDocument(value.photo);
        });
        setChromeVisible(chromeVisible);
        updateControls();
        ViewCompat.requestApplyInsets(root);
    }

    protected PhotoPagerAdapter createPhotoAdapter(List<String> sources, PhotoPagerAdapter.Callback callback) {
        return new PhotoPagerAdapter(sources, callback);
    }

    private String currentSource() { return photos.get(pager.getCurrentItem()); }
    private boolean ready() { return loads[pager.getCurrentItem()] == PhotoPagerAdapter.LoadState.READY; }
    private boolean busy() { return exports.isBusy() || pendingDocument != null; }

    private void updateControls() {
        if (pager == null || exports == null) return;
        int index = pager.getCurrentItem();
        counter.setText(getString(R.string.photo_viewer_counter, index + 1, photos.size()));
        previous.setVisibility(chromeVisible && index > 0 ? View.VISIBLE : View.GONE);
        next.setVisibility(chromeVisible && index + 1 < photos.size() ? View.VISIBLE : View.GONE);
        enabled(save, ready() && !busy());
        enabled(share, ready() && !busy());
        enabled(reset, zoomed);
    }

    private static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .4f);
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        top.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottom.setVisibility(visible ? View.VISIBLE : View.GONE);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars());
        else bars.hide(WindowInsetsCompat.Type.systemBars());
        updateControls();
    }

    private void move(int delta) {
        int target = pager.getCurrentItem() + delta;
        if (target < 0 || target >= photos.size()) return;
        adapter.resetZoom(pager.getCurrentItem());
        pager.setCurrentItem(target, false);
    }

    private void showActions(View anchor) {
        menu = new PopupMenu(new androidx.appcompat.view.ContextThemeWrapper(this,
                androidx.appcompat.R.style.ThemeOverlay_AppCompat_Light), anchor);
        menu.getMenu().add(0, SAVE, 0, R.string.photo_viewer_save).setEnabled(ready() && !busy());
        menu.getMenu().add(0, COPY, 1, R.string.photo_action_copy);
        menu.getMenu().add(0, SHARE, 2, R.string.photo_viewer_share).setEnabled(ready() && !busy());
        menu.getMenu().add(0, RESET, 3, R.string.photo_action_reset).setEnabled(zoomed);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == SAVE) onSavePhoto(currentSource());
            else if (item.getItemId() == COPY) copyPhotoLink();
            else if (item.getItemId() == SHARE) onSharePhoto(currentSource());
            else if (item.getItemId() == RESET) adapter.resetZoom(pager.getCurrentItem());
            else return false;
            return true;
        });
        menu.show();
    }

    protected void onSavePhoto(String source) {
        if (busy() || !ready()) return;
        toast(R.string.photo_action_preparing);
        exports.start(source, Build.VERSION.SDK_INT >= 29
                ? PhotoExportViewModel.Kind.SAVED : PhotoExportViewModel.Kind.DOCUMENT);
    }

    protected void onSharePhoto(String source) {
        if (!busy() && ready()) {
            toast(R.string.photo_action_preparing);
            exports.start(source, PhotoExportViewModel.Kind.SHARE);
        }
    }

    protected void copyPhotoLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.photo_viewer_photo), currentSource()));
            if (Build.VERSION.SDK_INT < 33) toast(R.string.photo_action_copied);
        }
    }

    protected void dispatchShare(PhotoExports.SavedPhoto photo) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".photo-files", photo.file);
            startActivity(Intent.createChooser(shareIntent(uri, photo.mimeType), getString(R.string.photo_viewer_share)));
        } catch (RuntimeException ignored) { toast(R.string.photo_action_error); }
    }

    static Intent shareIntent(Uri uri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Photo", uri));
        return intent;
    }

    private void chooseDocument(PhotoExports.SavedPhoto photo) {
        pendingDocument = Uri.fromFile(photo.file).toString();
        try {
            document.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(photo.mimeType).putExtra(Intent.EXTRA_TITLE, photo.file.getName()));
        } catch (RuntimeException ignored) {
            deleteStaged(pendingDocument);
            pendingDocument = null;
            toast(R.string.photo_action_error);
        }
        updateControls();
    }

    private static void deleteStaged(String source) {
        if (source != null && "file".equals(Uri.parse(source).getScheme())) {
            String path = Uri.parse(source).getPath();
            if (path != null) new File(path).delete();
        }
    }

    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (pager != null) state.putInt(STATE_INDEX, pager.getCurrentItem());
        state.putBoolean(STATE_CHROME, chromeVisible);
        state.putString(STATE_DOCUMENT, pendingDocument);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (menu != null) menu.dismiss();
        if (pager != null) pager.unregisterOnPageChangeCallback(pages);
        if (adapter != null) adapter.release();
        super.onDestroy();
    }
}
