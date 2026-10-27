package com.vktea.feed;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vktea.R;
import com.vktea.auth.Session;

import java.util.List;
import java.util.Objects;



/** Local, read-only access to saved story replies, including replies to expired stories. */
public final class StoryDraftsActivity extends AppCompatActivity {
    interface Store {
        StoryDraftStore.ListResult list(String account);
        StoryDraftStore.ClearResult clearIfMatches(String account, long owner, long story, String text);
    }
    interface StoreFactory { Store create(Context context); }
    static StoreFactory storeFactory = context -> {
        StoryDraftStore store = StoryDraftStore.create(context);
        return new Store() {
            @Override public StoryDraftStore.ListResult list(String account) { return store.list(account); }
            @Override public StoryDraftStore.ClearResult clearIfMatches(String account, long owner, long story, String text) {
                return store.clearIfMatches(account, owner, story, text);
            }
        };
    };


    private String account, token, secret;
    private Store store;
    private LinearLayout content;
    private AlertDialog detail;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        account = Session.getUserId(this); token = Session.getToken(this); secret = Session.getSecret(this);
        if (!sameSession()) { finish(); return; }
        store = storeFactory.create(getApplicationContext());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16); root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText(R.string.story_drafts_title); title.setTextSize(22);
        root.addView(title);
        TextView description = new TextView(this); description.setText(R.string.story_drafts_description);
        root.addView(description);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = new Button(this); close.setText(R.string.story_close); close.setOnClickListener(v -> finish()); root.addView(close);
        setContentView(root);
        refresh();
    }

    private boolean sameSession() {
        return account != null && !account.isEmpty() && token != null && !token.isEmpty()
                && Objects.equals(account, Session.getUserId(this)) && Objects.equals(token, Session.getToken(this))
                && Objects.equals(secret, Session.getSecret(this));
    }
    private boolean canDisplay() { return !isFinishing() && !isDestroyed() && sameSession(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    @Override protected void onResume() {
        super.onResume();
        if (!sameSession()) { if (detail != null) detail.dismiss(); finish(); }
    }

    private void refresh() {
        if (!canDisplay()) return;
        final int request = ++generation;
        content.removeAllViews();
        TextView loading = new TextView(this); loading.setText(R.string.story_drafts_loading); content.addView(loading);
        StoryDraftCoordinator.execute(() -> {
            StoryDraftStore.ListResult result;
            try { result = store.list(account); } catch (RuntimeException failure) { result = null; }
            StoryDraftStore.ListResult loaded = result;
            runOnUiThread(() -> {
                if (!canDisplay() || request != generation) return;
                content.removeAllViews();
                if (loaded == null || !loaded.complete) {
                    TextView error = new TextView(this); error.setText(R.string.story_drafts_read_error); content.addView(error);
                    Button retry = new Button(this); retry.setText(R.string.story_drafts_retry); retry.setOnClickListener(v -> refresh()); content.addView(retry);
                }
                if (loaded == null) return;
                List<StoryDraftStore.Draft> drafts = loaded.drafts;
                if (drafts.isEmpty() && loaded.complete) {
                    TextView empty = new TextView(this); empty.setText(R.string.story_drafts_empty); content.addView(empty);
                }
                for (StoryDraftStore.Draft draft : drafts) {
                    Button item = new Button(this);
                    item.setText(getString(R.string.story_draft_item, draft.ownerId, draft.storyId));
                    item.setAllCaps(false);
                    item.setOnClickListener(v -> showDraft(draft)); content.addView(item);
                }
            });
        });
    }

    private void showDraft(StoryDraftStore.Draft draft) {
        if (!canDisplay()) return;
        detail = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.story_draft_item, draft.ownerId, draft.storyId))
                .setMessage(draft.text)
                .setPositiveButton(R.string.story_draft_copy, (dialog, which) -> copy(draft.text))
                .setNeutralButton(R.string.story_draft_delete, (dialog, which) -> confirmDelete(draft))
                .setNegativeButton(android.R.string.cancel, null).create();
        detail.show();
    }
    private void copy(String text) {
        if (!canDisplay()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) { Toast.makeText(this, R.string.story_draft_copy_error, Toast.LENGTH_LONG).show(); return; }
        try {
            ClipData clip = ClipData.newPlainText(getString(R.string.story_drafts_title), text);
            if (Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.story_draft_copied, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) { Toast.makeText(this, R.string.story_draft_copy_error, Toast.LENGTH_LONG).show(); }
    }
    private void confirmDelete(StoryDraftStore.Draft draft) {
        if (!canDisplay()) return;
        detail = new AlertDialog.Builder(this).setMessage(R.string.story_draft_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.story_draft_delete, (dialog, which) -> delete(draft)).create();
        detail.show();
    }
    private void delete(StoryDraftStore.Draft draft) {
        if (!canDisplay()) return;
        ++generation;
        content.removeAllViews(); TextView loading = new TextView(this); loading.setText(R.string.story_drafts_loading); content.addView(loading);
        StoryDraftCoordinator.execute(() -> {
            StoryDraftStore.ClearResult result;
            try { result = sameSession() ? store.clearIfMatches(account, draft.ownerId, draft.storyId, draft.text) : StoryDraftStore.ClearResult.ERROR; }
            catch (RuntimeException failure) { result = StoryDraftStore.ClearResult.ERROR; }
            StoryDraftStore.ClearResult done = result;
            runOnUiThread(() -> {
                if (!canDisplay()) return;
                if (done == StoryDraftStore.ClearResult.CHANGED) Toast.makeText(this, R.string.story_draft_changed, Toast.LENGTH_LONG).show();
                else if (done != StoryDraftStore.ClearResult.DELETED) Toast.makeText(this, R.string.story_draft_delete_error, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }
    @Override protected void onDestroy() {
        generation++;
        if (detail != null) { detail.dismiss(); detail = null; }

        super.onDestroy();
    }
}
