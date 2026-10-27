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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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

import org.json.JSONArray;
import org.json.JSONObject;

/** Displays a single author's stories and stops playback while the activity is backgrounded. */
public class StoryViewerActivity extends AppCompatActivity {
    private static final String EXTRA_STORIES = "stories", EXTRA_NAME = "name";
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

    static void start(Context context, StoriesParser.Owner owner) {
        context.startActivity(new Intent(context, StoryViewerActivity.class)
                .putExtra(EXTRA_NAME, owner.name).putExtra(EXTRA_STORIES, owner.stories.toString()));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { stories = new JSONArray(getIntent().getStringExtra(EXTRA_STORIES)); }
        catch (Exception e) { finish(); return; }
        if (stories.length() == 0) { finish(); return; }
        index = Math.max(0, Math.min(stories.length() - 1, state == null ? 0 : state.getInt("index", 0)));
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
        Button close = button(getString(R.string.story_close)); close.setOnClickListener(v -> finish()); top.addView(close);
        overlay.addView(top);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setTextSize(16);
        overlay.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        web = button(getString(R.string.story_open_vk)); web.setVisibility(View.GONE); web.setOnClickListener(v -> openWeb());
        overlay.addView(web, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(this); controls.setPadding(dp(8), 0, dp(8), dp(12));
        Button previous = button(getString(R.string.story_previous)); previous.setOnClickListener(v -> previous());
        Button next = button(getString(R.string.story_next)); next.setOnClickListener(v -> next());
        controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        overlay.addView(controls); setContentView(root);
    }
    private Button button(String text) { Button b = new Button(this); b.setText(text); return b; }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private void show() {
        handler.removeCallbacks(advance); generation++; int request = generation;
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
                    if (generation == request && !isFinishing()) { status.setText(""); if (active) handler.postDelayed(advance, 5000); }
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
                    else if (state == Player.STATE_ENDED && active) next();
                }
                @Override public void onPlayerError(PlaybackException error) { if (generation == request) unavailable(); }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url))); player.prepare(); player.setPlayWhenReady(active);
        } else unavailable();
    }
    private void unavailable() { handler.removeCallbacks(advance); releasePlayer(); status.setText(R.string.story_unavailable);
        JSONObject story = stories.optJSONObject(index);
        web.setVisibility(story != null && story.optLong("owner_id") != 0 && story.optLong("id") > 0 ? View.VISIBLE : View.GONE); }
    private void previous() { if (index > 0) { index--; show(); } }
    private void next() { if (index + 1 < stories.length()) { index++; show(); } else finish(); }
    private void openWeb() {
        JSONObject story = stories.optJSONObject(index); if (story == null) return;
        long owner = story.optLong("owner_id"), id = story.optLong("id");
        if (owner == 0 || id <= 0) return;
        startActivity(new Intent(this, AuthWebViewActivity.class).setData(Uri.parse("https://m.vk.com/story" + owner + "_" + id)));
    }
    private void releasePlayer() { if (player != null) { video.setPlayer(null); player.release(); player = null; } }
    @Override protected void onStart() { super.onStart(); if (isFinishing() || stories == null || image == null) return; active = true; show(); }
    @Override protected void onStop() { active = false; generation++; handler.removeCallbacks(advance); releasePlayer(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("index", index); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { handler.removeCallbacks(advance); releasePlayer(); super.onDestroy(); }
}
