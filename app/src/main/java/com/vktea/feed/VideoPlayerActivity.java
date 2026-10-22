package com.vktea.feed;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

public class VideoPlayerActivity extends AppCompatActivity {

    public static void start(android.content.Context ctx, long ownerId, long videoId) {
        ctx.startActivity(new Intent(ctx, VideoPlayerActivity.class)
                .putExtra("owner_id", ownerId)
                .putExtra("video_id", videoId));
    }

    private long ownerId;
    private long videoId;
    private ExoPlayer player;
    private PlayerView playerView;
    private CircularProgressIndicator progress;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        ownerId = getIntent().getLongExtra("owner_id", 0);
        videoId = getIntent().getLongExtra("video_id", 0);

        playerView = findViewById(R.id.video_view);
        progress = findViewById(R.id.video_progress);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    progress.setVisibility(android.view.View.GONE);
                } else if (state == androidx.media3.common.Player.STATE_IDLE) {
                    fallbackToWeb();
                }
            }
        });

        loadVideoUrl();
    }

    private void loadVideoUrl() {
        String direct = getIntent().getStringExtra("direct_url");
        if (direct != null && !direct.isEmpty()) {
            play(direct);
            return;
        }
        VKApi.get(this, "video.get", "videos=" + ownerId + "_" + videoId, new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                String url = null;
                try {
                    JSONArray items = response.getJSONObject("response").getJSONArray("items");
                    if (items.length() > 0) {
                        JSONObject files = items.getJSONObject(0).optJSONObject("files");
                        if (files != null) {
                            String[] keys = {"mp4_1080", "mp4_720", "mp4_480", "mp4_360", "mp4_240"};
                            for (String key : keys) {
                                String u = files.optString(key, null);
                                if (u != null && !u.isEmpty()) {
                                    url = u;
                                    break;
                                }
                            }
                            if (url == null) {
                                url = files.optString("hls", null);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                if (url != null) {
                    play(url);
                } else {
                    fallbackToWeb();
                }
            }

            @Override
            public void error(Exception e) {
                fallbackToWeb();
            }
        });
    }

    private void play(String url) {
        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void fallbackToWeb() {
        runOnUiThread(() -> {
            startActivity(new Intent(this, com.vktea.auth.AuthWebViewActivity.class)
                    .setData(Uri.parse("https://m.vk.com/video" + ownerId + "_" + videoId)));
            finish();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
