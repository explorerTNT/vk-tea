package com.vktea.videos;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;

/** Local export; no edit-list optimization, so discarded footage is not retained as hidden pre-roll. */
@UnstableApi
final class VideoTrimmer {
    interface Callback { void done(File file); }
    private Transformer transformer;
    private File output;
    void start(Context context, Uri source, long startMs, long endMs, Callback callback) throws IOException {
        // A process kill cannot run lifecycle cleanup; discard abandoned exports on the next trim.
        File[] stale = context.getCacheDir().listFiles((dir, name) -> name.startsWith("vk-video-trim-"));
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (stale != null) for (File file : stale) if (file.lastModified() < cutoff) file.delete();
        output = File.createTempFile("vk-video-trim-", ".mp4", context.getCacheDir());
        MediaItem media = new MediaItem.Builder().setUri(source).setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build();
        transformer = new Transformer.Builder(context).addListener(new Transformer.Listener() {
            @Override public void onCompleted(Composition composition, ExportResult result) { transformer = null; callback.done(output); }
            @Override public void onError(Composition composition, ExportResult result, ExportException error) { transformer = null; cleanup(); callback.done(null); }
        }).build();
        transformer.start(media, output.getAbsolutePath());
    }
    void cleanup() { if (transformer != null) { transformer.cancel(); transformer = null; } if (output != null) { output.delete(); output = null; } }
}
