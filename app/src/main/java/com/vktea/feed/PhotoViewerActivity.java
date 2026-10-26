package com.vktea.feed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.vktea.R;
import com.vktea.share.ShareActivity;
import com.vktea.share.ShareAttachment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PhotoViewerActivity extends AppCompatActivity {
    public static final String EXTRA_PHOTOS = "photos", EXTRA_INDEX = "index";
    private static final String STATE_INDEX = "photo_index", STATE_CHROME = "photo_chrome",
            STATE_DOCUMENT = "photo_document", STATE_DISCUSSION = "photo_discussion";
    private static final int SAVE = 1, COPY = 2, RESET = 4, REFRESH = 5;
    private final List<String> photos = new ArrayList<>();
    private final List<ViewerPhoto> photoDetails = new ArrayList<>();
    private ViewPager2 pager;
    private PhotoPagerAdapter adapter;
    private PhotoExportViewModel exports;
    private View top, bottom, previous, next;
    private View like, comments, repost;
    private TextView likeCount, commentsCount, repostCount;
    private ImageView likeIcon;
    private PhotoDismissLayout dismissLayout;
    private PhotoSocialViewModel social;
    private PhotoSocialController socialController;
    private PhotoSocialViewModel.State socialState;
    private TextView counter;
    private boolean chromeVisible = true, zoomed;
    private PhotoPagerAdapter.LoadState[] loads;
    private PopupMenu menu;
    private String pendingDocument;
    private boolean refreshAfterDiscussion;

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
            if (social != null) social.select(currentPhoto());
            updateControls();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTheme(R.style.Theme_VKTea_PhotoViewer);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ArrayList<String> input = getIntent().getStringArrayListExtra(EXTRA_PHOTOS);
        ArrayList<ViewerPhoto> details = getIntent().getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        if (details != null && !details.isEmpty()) {
            input = new ArrayList<>();
            for (ViewerPhoto photo : details) input.add(photo == null ? null : photo.url);
        }
        int requested = Math.max(0, getIntent().getIntExtra(EXTRA_INDEX, 0)), filteredIndex = 0;
        if (input != null) for (int i = 0; i < input.size(); i++) {
            String source = input.get(i);
            if (source != null && !source.trim().isEmpty()) {
                if (i < requested) filteredIndex++;
                photos.add(source);
                photoDetails.add(details != null && i < details.size() && details.get(i) != null
                        ? details.get(i) : ViewerPhoto.fromUrl(source));
            }
        }
        if (photos.isEmpty()) { finish(); return; }
        int initial = Math.min(photos.size() - 1, state == null ? filteredIndex
                : Math.max(0, state.getInt(STATE_INDEX, filteredIndex)));
        chromeVisible = state == null || state.getBoolean(STATE_CHROME, true);
        pendingDocument = state == null ? null : state.getString(STATE_DOCUMENT);
        refreshAfterDiscussion = state != null && state.getBoolean(STATE_DISCUSSION, false);
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
        like = findViewById(R.id.viewer_like);
        comments = findViewById(R.id.viewer_comments);
        repost = findViewById(R.id.viewer_repost);
        likeIcon = findViewById(R.id.viewer_like_icon);
        likeCount = findViewById(R.id.viewer_like_count);
        commentsCount = findViewById(R.id.viewer_comments_count);
        repostCount = findViewById(R.id.viewer_repost_count);
        counter = findViewById(R.id.viewer_counter);
        pager = findViewById(R.id.photo_pager);
        loads = new PhotoPagerAdapter.LoadState[photos.size()];
        exports = new ViewModelProvider(this).get(PhotoExportViewModel.class);
        social = createPhotoSocialViewModel();
        socialController = new PhotoSocialController(this, social);
        adapter = createPhotoAdapter(photos, new PhotoPagerAdapter.Callback() {
            @Override public void onToggleChrome() { setChromeVisible(!chromeVisible); }
            @Override public void onZoomChanged(int position, boolean value) {
                if (position != pager.getCurrentItem()) return;
                zoomed = value;
                pager.setUserInputEnabled(!value && (dismissLayout == null || !dismissLayout.isMultiTouchActive()));
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
        dismissLayout = findViewById(R.id.viewer_middle);
        dismissLayout.bind(pager, new PhotoDismissLayout.Listener() {
            @Override public boolean canDismiss() {
                return !zoomed && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE;
            }
            @Override public void onProgress(float fraction) {
                top.setAlpha(1f - fraction);
                bottom.setAlpha(1f - fraction);
                previous.setAlpha(1f - fraction);
                next.setAlpha(1f - fraction);
                pager.setUserInputEnabled(fraction == 0f && !zoomed);
            }
            @Override public void onDismiss() { finish(); }
            @Override public void onMultiTouch() { pager.setUserInputEnabled(false); }
            @Override public void onTouchEnd() { pager.setUserInputEnabled(!zoomed); }
        });
        findViewById(R.id.viewer_back).setOnClickListener(view -> finish());
        findViewById(R.id.viewer_more).setOnClickListener(this::showActions);
        previous.setOnClickListener(view -> move(-1));
        next.setOnClickListener(view -> move(1));
        like.setOnClickListener(view -> social.toggleLike());
        comments.setOnClickListener(view -> {
            refreshAfterDiscussion = true;
            openPhotoDiscussion(activePhoto());
        });
        repost.setOnClickListener(view -> openShare(ShareAttachment.photo(activePhoto())));
        social.state().observe(this, value -> { socialState = value; updateControls(); });
        social.select(currentPhoto());
        exports.state().observe(this, value -> {
            updateControls();
            if (value == null || value.busy || value.handled) return;
            value.handled = true;
            if (value.kind == PhotoExportViewModel.Kind.ERROR) toast(R.string.photo_action_error);
            else if (value.kind == PhotoExportViewModel.Kind.SAVED) toast(R.string.photo_action_saved);
            else if (value.kind == PhotoExportViewModel.Kind.DOCUMENT) chooseDocument(value.photo);
        });
        setChromeVisible(chromeVisible);
        updateControls();
        ViewCompat.requestApplyInsets(root);
    }

    protected PhotoPagerAdapter createPhotoAdapter(List<String> sources, PhotoPagerAdapter.Callback callback) {
        return new PhotoPagerAdapter(sources, callback);
    }

    protected PhotoSocialViewModel createPhotoSocialViewModel() {
        return new ViewModelProvider(this).get(PhotoSocialViewModel.class);
    }

    protected void openShare(ShareAttachment attachment) {
        refreshAfterDiscussion = true;
        ShareActivity.start(this, attachment);
    }

    protected void openPhotoDiscussion(ViewerPhoto photo) { PhotoDiscussionActivity.start(this, photo); }

    private String currentSource() { return photos.get(pager.getCurrentItem()); }
    private ViewerPhoto currentPhoto() { return photoDetails.get(pager.getCurrentItem()); }
    private ViewerPhoto activePhoto() {
        return socialState != null && socialState.photo != null
                && socialState.photo.ownerId == currentPhoto().ownerId && socialState.photo.id == currentPhoto().id
                ? socialState.photo : currentPhoto();
    }
    private boolean ready() { return loads[pager.getCurrentItem()] == PhotoPagerAdapter.LoadState.READY; }
    private boolean busy() { return exports.isBusy() || pendingDocument != null; }

    private void updateControls() {
        if (pager == null || exports == null) return;
        int index = pager.getCurrentItem();
        counter.setText(getString(R.string.photo_viewer_counter, index + 1, photos.size()));
        previous.setVisibility(chromeVisible && index > 0 ? View.VISIBLE : View.GONE);
        next.setVisibility(chromeVisible && index + 1 < photos.size() ? View.VISIBLE : View.GONE);
        boolean samePhoto = socialState != null && socialState.photo != null
                && socialState.photo.ownerId == currentPhoto().ownerId && socialState.photo.id == currentPhoto().id;
        ViewerPhoto photo = samePhoto ? socialState.photo : currentPhoto();
        boolean available = samePhoto && photo.hasIdentity() && !socialState.loading && !socialState.busy;
        enabled(like, available && (photo.canLike || photo.liked));
        enabled(comments, available);
        enabled(repost, available);
        count(like, likeCount, photo.likes, photo.liked ? R.string.photo_viewer_unlike : R.string.photo_viewer_like);
        count(comments, commentsCount, photo.comments, R.string.photo_viewer_comments);
        count(repost, repostCount, photo.reposts, R.string.photo_viewer_repost);
        ImageViewCompat.setImageTintList(likeIcon, ColorStateList.valueOf(photo.liked ? 0xffff6b7d : 0xffffffff));
        likeIcon.setAlpha(like.getAlpha());
        likeCount.setAlpha(like.getAlpha());
        findViewById(R.id.viewer_comments_icon).setAlpha(comments.getAlpha());
        commentsCount.setAlpha(comments.getAlpha());
        findViewById(R.id.viewer_repost_icon).setAlpha(repost.getAlpha());
        repostCount.setAlpha(repost.getAlpha());
    }

    private void count(View target, TextView label, int number, int action) {
        label.setText(number > 0 ? FeedAdapter.formatCount(number) : "");
        target.setContentDescription(getString(R.string.photo_viewer_action_count, getString(action), number));
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
        menu.getMenu().add(0, RESET, 3, R.string.photo_action_reset).setEnabled(zoomed);
        if (currentPhoto().hasIdentity() && socialState != null && socialState.error != null) {
            menu.getMenu().add(0, REFRESH, 4, R.string.photo_action_refresh)
                    .setEnabled(!socialState.loading && !socialState.busy);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == SAVE) onSavePhoto(currentSource());
            else if (item.getItemId() == COPY) copyPhotoLink();
            else if (item.getItemId() == RESET) adapter.resetZoom(pager.getCurrentItem());
            else if (item.getItemId() == REFRESH) social.refresh();
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

    protected void copyPhotoLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ViewerPhoto photo = currentPhoto();
            String link = photo.hasIdentity() ? "https://vk.com/photo" + photo.ownerId + "_" + photo.id : currentSource();
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.photo_viewer_photo), link));
            if (Build.VERSION.SDK_INT < 33) toast(R.string.photo_action_copied);
        }
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
        state.putBoolean(STATE_DISCUSSION, refreshAfterDiscussion);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (refreshAfterDiscussion && social != null) {
            refreshAfterDiscussion = false;
            social.refresh();
        }
    }

    @Override protected void onDestroy() {
        if (menu != null) menu.dismiss();
        if (pager != null) pager.unregisterOnPageChangeCallback(pages);
        if (adapter != null) adapter.release();
        if (socialController != null) socialController.destroy();
        if (dismissLayout != null) dismissLayout.reset();
        super.onDestroy();
    }
}
