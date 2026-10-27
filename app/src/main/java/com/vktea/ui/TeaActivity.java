package com.vktea.ui;

import android.content.Context;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.vktea.R;

/** Applies a saved palette before the activity inflates its content. */
public class TeaActivity extends AppCompatActivity {
    private long paletteRevision;
    private boolean refreshing;

    @Override protected void attachBaseContext(Context base) {
        boolean viewer = this instanceof com.vktea.feed.PhotoViewerActivity
                || this instanceof com.vktea.feed.VideoPlayerActivity;
        super.attachBaseContext(viewer ? base : AppearanceSettings.wrapBaseContext(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean viewer = this instanceof com.vktea.feed.PhotoViewerActivity
                || this instanceof com.vktea.feed.VideoPlayerActivity;
        if (!viewer && !AppearanceSettings.applyPalette(this)) {
            AppearanceSettings.recoverFailedPalette(this);
            android.widget.Toast.makeText(this, R.string.theme_catalog_apply_failed, android.widget.Toast.LENGTH_LONG).show();
            refreshing = true;
            recreate();
            return;
        }
        paletteRevision = AppearanceSettings.paletteRevision(this);
        CoffeePalette palette = AppearanceSettings.getPalette(this);
        if (palette != null && !viewer) {
            getWindow().setStatusBarColor(palette.colors.get("statusbar"));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (!refreshing && paletteRevision != AppearanceSettings.paletteRevision(this)) {
            refreshing = true;
            recreate();
        }
    }
}
