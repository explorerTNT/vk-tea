package com.vktea.feed;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.vktea.R;
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Displays a single author's stories and stops playback while the activity is backgrounded. */
public class StoryViewerActivity extends AppCompatActivity {
    private static final String EXTRA_STORIES = "stories", EXTRA_NAME = "name", EXTRA_CACHE = "story_cache";
    private static final class CachedStories {
        final JSONArray stories; final String token, secret, user;
        CachedStories(Context context, JSONArray stories) {
            this.stories = stories; token = Session.getToken(context); secret = Session.getSecret(context); user = Session.getUserId(context);
        }
        boolean matches(Context context) {
            return java.util.Objects.equals(token, Session.getToken(context)) && java.util.Objects.equals(secret, Session.getSecret(context))
                    && java.util.Objects.equals(user, Session.getUserId(context));
        }
    }
    private static final Map<String, CachedStories> STORY_CACHE = new LinkedHashMap<String, CachedStories>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedStories> eldest) { return size() > 16; }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::next;
    private JSONArray stories;
    private int index, generation;
    private boolean active;
    private ImageView image;
    private PlayerView video;
    private ExoPlayer player;
    private TextView heading, status;
    private Button web;
    private Button reply, reaction, nativeReaction;
    private Button close, previous, next;
    private StoryActionsViewModel actions;
    private boolean dialogOpen, mediaReady, awaitingReply;

    static void start(Context context, StoriesParser.Owner owner) {
        String key = UUID.randomUUID().toString();
        synchronized (STORY_CACHE) { STORY_CACHE.put(key, new CachedStories(context, owner.stories)); }
        context.startActivity(new Intent(context, StoryViewerActivity.class)
                .putExtra(EXTRA_NAME, owner.name).putExtra(EXTRA_CACHE, key));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String cacheKey = getIntent().getStringExtra(EXTRA_CACHE);
            if (cacheKey != null) { synchronized (STORY_CACHE) {
                CachedStories cached = STORY_CACHE.get(cacheKey);
                if (cached != null && cached.matches(this)) stories = cached.stories;
            } }
            else stories = new JSONArray(getIntent().getStringExtra(EXTRA_STORIES)); // compatibility with existing local callers
            if (stories == null) { finish(); return; }
        }
        catch (Exception e) { finish(); return; }
        if (stories.length() == 0) { finish(); return; }
        index = Math.max(0, Math.min(stories.length() - 1, state == null ? 0 : state.getInt("index", 0)));
        awaitingReply = state != null && state.getBoolean("awaiting_reply", false);
        actions = new ViewModelProvider(this).get(StoryActionsViewModel.class);
        actions.events.observe(this, event -> {
            JSONObject current = stories.optJSONObject(index);
            if (event != null && current != null && event.key.equals(StoryActionsViewModel.storyKey(current))) {
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show();
                if (awaitingReply && !actions.isPending(current)) { awaitingReply = false; resumeAfterDialog(); }
                updateActions();
            }
        });
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        video = new PlayerView(this); video.setUseController(false); video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(8), dp(12), dp(8), 0);
        heading = new TextView(this); heading.setTextColor(Color.WHITE); heading.setTextSize(16);
        top.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        close = button(getString(R.string.story_close)); close.setOnClickListener(v -> closeViewer()); top.addView(close);
        overlay.addView(top);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setTextSize(16);
        overlay.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        web = button(getString(R.string.story_open_vk)); web.setVisibility(View.GONE); web.setOnClickListener(v -> openWeb());
        overlay.addView(web, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actionsRow = new LinearLayout(this);
        reply = button(getString(R.string.story_reply)); reply.setOnClickListener(v -> replyDialog());
        reaction = button(getString(R.string.story_reaction_message)); reaction.setOnClickListener(v -> reactionDialog());
        nativeReaction = button(getString(R.string.story_native_reaction)); nativeReaction.setOnClickListener(v -> openWeb());
        actionsRow.addView(reply, new LinearLayout.LayoutParams(0, -2, 1));
        actionsRow.addView(reaction, new LinearLayout.LayoutParams(0, -2, 1));
        actionsRow.addView(nativeReaction, new LinearLayout.LayoutParams(0, -2, 1));
        overlay.addView(actionsRow);
        LinearLayout controls = new LinearLayout(this); controls.setPadding(dp(8), 0, dp(8), dp(12));
        previous = button(getString(R.string.story_previous)); previous.setOnClickListener(v -> previous());
        next = button(getString(R.string.story_next)); next.setOnClickListener(v -> next());
        controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        overlay.addView(controls); setContentView(root);
    }
    private Button button(String text) { Button b = new Button(this); b.setText(text); return b; }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private void show() {
        handler.removeCallbacks(advance); generation++; int request = generation;
        mediaReady = false; updateActions();
        releasePlayer(); Glide.with(this).clear(image); image.setImageDrawable(null); image.setVisibility(View.GONE); video.setVisibility(View.GONE);
        status.setText(""); web.setVisibility(View.GONE);
        JSONObject story = stories.optJSONObject(index);
        if (story == null) { unavailable(); return; }
        heading.setText(getString(R.string.story_progress, getIntent().getStringExtra(EXTRA_NAME), index + 1, stories.length()));
        String url = StoriesParser.safeUrl(story.optString("url", ""));
        if (url.isEmpty()) { unavailable(); return; }
        if ("photo".equals(story.optString("type"))) {
            image.setVisibility(View.VISIBLE); status.setText(R.string.story_loading);
            Glide.with(this).load(url).listener(new RequestListener<android.graphics.drawable.Drawable>() {
                @Override public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean first) {
                    if (generation == request && !isFinishing()) unavailable(); return false;
                }
                @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, DataSource source, boolean first) {
                    if (generation == request && !isFinishing()) { status.setText(""); ready(); if (active && !dialogOpen && !sending()) handler.postDelayed(advance, 5000); }
                    return false;
                }
            }).into(image);
        } else if ("video".equals(story.optString("type"))) {
            video.setVisibility(View.VISIBLE); status.setText(R.string.story_loading);
            player = new ExoPlayer.Builder(this).build(); video.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (generation != request) return;
                    if (state == Player.STATE_READY) status.setText("");
                    else if (state == Player.STATE_ENDED && active && !sending()) next();
                }
                @Override public void onRenderedFirstFrame() { if (generation == request) ready(); }
                @Override public void onPlayerError(PlaybackException error) { if (generation == request) unavailable(); }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url))); player.prepare(); player.setPlayWhenReady(active && !sending());
        } else unavailable();
    }
    private void unavailable() { mediaReady = false; updateActions(); handler.removeCallbacks(advance); releasePlayer(); status.setText(R.string.story_unavailable);
        JSONObject story = stories.optJSONObject(index);
        web.setVisibility(story != null && story.optLong("owner_id") != 0 && story.optLong("id") > 0 ? View.VISIBLE : View.GONE); }
    private boolean sending() { return actions != null && stories != null && actions.isPending(stories.optJSONObject(index)); }
    private void closeViewer() { if (sending()) Toast.makeText(this, R.string.story_sending_wait, Toast.LENGTH_SHORT).show(); else finish(); }
    @Override public void onBackPressed() { if (sending()) closeViewer(); else super.onBackPressed(); }
    private void previous() { if (!sending() && index > 0) { index--; show(); } }
    private void next() { if (sending()) return; if (index + 1 < stories.length()) { index++; show(); } else finish(); }
    private void openWeb() {
        JSONObject story = stories.optJSONObject(index); if (story == null) return;
        long owner = story.optLong("owner_id"), id = story.optLong("id");
        if (owner == 0 || id <= 0) return;
        startActivity(new Intent(this, AuthWebViewActivity.class).setData(Uri.parse("https://m.vk.com/story" + owner + "_" + id)));
    }
    private void ready() {
        if (!active || dialogOpen || mediaReady) return;
        mediaReady = true; JSONObject story = stories.optJSONObject(index);
        if (story != null) actions.markSeen(story);
        updateActions();
    }
    private void updateActions() {
        if (reply == null || stories == null || actions == null) return;
        JSONObject story = stories.optJSONObject(index);
        boolean identity = StoryActionsViewModel.identity(story);
        boolean canSend = mediaReady && actions.sameSession() && StoryActionsViewModel.canReply(story, Session.getUserId(this)) && !actions.isPending(story);
        reply.setEnabled(canSend); reaction.setEnabled(canSend); nativeReaction.setEnabled(identity && !sending());
        if (close != null) close.setEnabled(!sending());
        if (previous != null) previous.setEnabled(!sending());
        if (next != null) next.setEnabled(!sending());
    }
    private void pauseForDialog() {
        dialogOpen = true; handler.removeCallbacks(advance);
        if (player != null) player.setPlayWhenReady(false);
    }
    private void resumeAfterDialog() {
        dialogOpen = false;
        if (!active || isFinishing() || sending() || awaitingReply) return;
        if (!mediaReady) show();
        else if (player != null) player.setPlayWhenReady(true);
        else if (mediaReady) handler.postDelayed(advance, 5000);
    }
    private void replyDialog() {
        JSONObject story = stories.optJSONObject(index);
        if (!mediaReady || !StoryActionsViewModel.canReply(story, Session.getUserId(this)) || actions.isPending(story)) return;
        pauseForDialog(); EditText input = new EditText(this); input.setHint(R.string.story_reply_hint);
        input.setMaxLines(5); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.story_reply).setView(input)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.story_continue, null).create();
        dialog.setOnDismissListener(v -> { if (!dialogOpen) return; resumeAfterDialog(); });
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String message = input.getText().toString().trim(); if (message.isEmpty()) { input.setError(getString(R.string.story_reply_hint)); return; }
            dialog.setOnDismissListener(null); dialog.dismiss(); confirmSend(story, message, false);
        }));
        dialog.show();
    }
    private void reactionDialog() {
        JSONObject story = stories.optJSONObject(index);
        if (!mediaReady || !StoryActionsViewModel.canReply(story, Session.getUserId(this)) || actions.isPending(story)) return;
        pauseForDialog();
        String[] emojis = {"❤️", "😂", "😮", "😢", "🔥", "👏"};
        boolean[] handoff = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.story_reaction_message)
                .setItems(emojis, (d, choice) -> { handoff[0] = true; confirmSend(story, emojis[choice], true); })
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnDismissListener(v -> { if (!handoff[0]) resumeAfterDialog(); }); dialog.show();
    }
    private void confirmSend(JSONObject story, String text, boolean emoji) {
        pauseForDialog();
        new AlertDialog.Builder(this).setTitle(R.string.story_send_confirm_title)
                .setMessage(getString(emoji ? R.string.story_reaction_confirm : R.string.story_reply_confirm,
                        getIntent().getStringExtra(EXTRA_NAME), text))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.story_send, (d, which) -> {
                    awaitingReply = true;
                    if (!actions.sendReply(story, text)) { awaitingReply = false; Toast.makeText(this, R.string.story_send_blocked, Toast.LENGTH_LONG).show(); }
                    updateActions();
                }).setOnDismissListener(v -> resumeAfterDialog()).show();
    }
    private void releasePlayer() { if (player != null) { video.setPlayer(null); player.release(); player = null; } }
    @Override protected void onStart() { super.onStart(); if (isFinishing() || stories == null || image == null) return; active = true; show(); }
    @Override protected void onStop() { active = false; generation++; handler.removeCallbacks(advance); releasePlayer(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("index", index); state.putBoolean("awaiting_reply", awaitingReply); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        handler.removeCallbacks(advance); releasePlayer();
        if (isFinishing()) { synchronized (STORY_CACHE) { STORY_CACHE.remove(getIntent().getStringExtra(EXTRA_CACHE)); } }
        super.onDestroy();
    }
}
