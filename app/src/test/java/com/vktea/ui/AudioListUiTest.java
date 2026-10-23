package com.vktea.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.audio.AudioUi;
import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic queue/UI tests never connect to VK or read the user's files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioListUiTest {
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private SyntheticDocumentProvider provider;

    @Before
    public void prepareActivityAndSyntheticDocuments() {
        controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        activity = controller.get();
        provider = new SyntheticDocumentProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "synthetic.audio.list";
        provider.attachInfo(activity, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void closeActivity() {
        controller.close();
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void selectedTrackHasSeparateMarkerDarkTitleDurationAndLocalIndicator() {
        AudioQueueAdapter adapter = adapter(new AtomicInteger(), new AtomicInteger());
        adapter.submit(Arrays.asList(track("one", "Synthetic title", "Synthetic artist", 138_000)), 0);
        RecyclerView parent = new RecyclerView(activity);
        parent.setLayoutManager(new LinearLayoutManager(activity));
        AudioQueueAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);

        adapter.onBindViewHolder(holder, 0);

        assertEquals("Synthetic title", holder.title.getText().toString());
        assertEquals("Synthetic artist", holder.artist.getText().toString());
        assertEquals(ContextCompat.getColor(activity, R.color.audio_text), holder.title.getCurrentTextColor());
        assertEquals(View.VISIBLE, holder.current.getVisibility());
        assertEquals(View.VISIBLE, holder.local.getVisibility());
        assertEquals("2:18", holder.duration.getText().toString());

        adapter.submit(Arrays.asList(track("one", "Synthetic title", "Synthetic artist", 138_000)), -1);
        adapter.onBindViewHolder(holder, 0);
        assertEquals(View.GONE, holder.current.getVisibility());
        assertEquals(ContextCompat.getColor(activity, R.color.audio_text), holder.title.getCurrentTextColor());
    }

    @Test
    public void localFilterPreservesQueueIndicesForClickAndLongPress() {
        AtomicInteger clicked = new AtomicInteger(-1);
        AtomicInteger held = new AtomicInteger(-1);
        AudioQueueAdapter adapter = adapter(clicked, held);
        adapter.submit(Arrays.asList(
                track("one", "Other song", "Another artist", 1000),
                track("two", "Desired song", "Target artist", 2000),
                track("three", "Third song", "Another artist", 3000)), 1);
        adapter.setQuery("TARGET ARTIST");
        RecyclerView queue = new RecyclerView(activity);
        queue.setLayoutManager(new LinearLayoutManager(activity));
        queue.setItemAnimator(null);
        queue.setAdapter(adapter);
        activity.setContentView(queue);
        int width = Math.round(400 * activity.getResources().getDisplayMetrics().density);
        int height = Math.round(600 * activity.getResources().getDisplayMetrics().density);
        queue.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        queue.layout(0, 0, width, height);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, adapter.getItemCount());
        assertEquals(1, adapter.queueIndexAt(0));
        RecyclerView.ViewHolder holder = queue.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        holder.itemView.performClick();
        holder.itemView.performLongClick();

        assertEquals(1, clicked.get());
        assertEquals(1, held.get());
        adapter.setQuery("");
        assertEquals(3, adapter.getItemCount());
        assertEquals(0, adapter.queueIndexAt(0));
        assertEquals(2, adapter.queueIndexAt(2));
    }

    @Test
    public void filterUsesUpdatedQueuePositionsAfterTheServiceReordersItems() {
        AudioQueueAdapter adapter = adapter(new AtomicInteger(), new AtomicInteger());
        MediaItem first = track("one", "First", "Artist", 1000);
        MediaItem second = track("two", "Second", "Artist", 2000);
        adapter.submit(Arrays.asList(first, second), 1);
        adapter.setQuery("second");
        assertEquals(1, adapter.queueIndexAt(0));

        adapter.submit(Arrays.asList(second, first), 0);

        assertEquals(1, adapter.getItemCount());
        assertEquals(0, adapter.queueIndexAt(0));
    }

    @Test
    public void metadataAndSizeAreReadFromSyntheticContentOnAWorker() throws Exception {
        Uri uri = Uri.parse("content://synthetic.audio.list/tagged");
        DataSource source = DataSource.toDataSource(activity, uri);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_TITLE,
                "Actual synthetic title");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_ARTIST,
                "Actual synthetic artist");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "138000");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_BITRATE, "320000");
        FutureTask<MediaItem> work = new FutureTask<>(() -> AudioFragment.readDocument(activity, uri));
        new Thread(work, "synthetic-audio-metadata").start();

        MediaItem item = work.get(5, TimeUnit.SECONDS);

        assertNotNull(item);
        assertFalse(provider.queriedOnMain);
        assertEquals(1, provider.queries.get());
        assertEquals("Actual synthetic title", item.mediaMetadata.title.toString());
        assertEquals("Actual synthetic artist", item.mediaMetadata.artist.toString());
        assertEquals(AudioUi.mediaId(uri), item.mediaId);
        assertEquals(138000, item.mediaMetadata.extras.getLong(AudioUi.EXTRA_DURATION_MS));
        assertEquals(320000, item.mediaMetadata.extras.getLong(AudioUi.EXTRA_BITRATE));
        assertEquals(98304, item.mediaMetadata.extras.getLong(AudioUi.EXTRA_FILE_SIZE));
        assertEquals(uri, item.localConfiguration.uri);
    }

    @Test
    public void missingOrMalformedTagsKeepFilenameAndDoNotInventDurationOrBitrate() throws Exception {
        Uri uri = Uri.parse("content://synthetic.audio.list/untagged");
        DataSource source = DataSource.toDataSource(activity, uri);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "-1");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_BITRATE, "unknown");
        FutureTask<MediaItem> work = new FutureTask<>(() -> AudioFragment.readDocument(activity, uri));
        new Thread(work, "synthetic-audio-untagged").start();

        MediaItem item = work.get(5, TimeUnit.SECONDS);

        assertNotNull(item);
        assertEquals("synthetic-file.mp3", item.mediaMetadata.title.toString());
        assertEquals(activity.getString(R.string.audio_local_file), item.mediaMetadata.artist.toString());
        assertFalse(item.mediaMetadata.extras.containsKey(AudioUi.EXTRA_DURATION_MS));
        assertFalse(item.mediaMetadata.extras.containsKey(AudioUi.EXTRA_BITRATE));
        assertEquals(98304, item.mediaMetadata.extras.getLong(AudioUi.EXTRA_FILE_SIZE));
    }

    @Test
    public void documentImportRejectsNonContentUrisWithoutQueryingAProvider() {
        assertNull(AudioFragment.readDocument(activity, Uri.parse("https://synthetic.invalid/audio.mp3")));
        assertNull(AudioFragment.readDocument(activity, Uri.parse("file:///synthetic/audio.mp3")));
        assertEquals(0, provider.queries.get());
    }

    @Test
    public void emptyLibraryOffersOnlyDocumentImportWhilePlaybackIsUnavailable() {
        Session.save(activity, "synthetic-list-token", "1");
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(activity, AudioPlaybackService.class));
        FrameLayout container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        activity.setContentView(container);
        AudioFragment fragment = new AudioFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(container.getId(), fragment).commitNow();
        shadowOf(Looper.getMainLooper()).idle();
        View root = fragment.requireView();

        assertFalse(root.findViewById(R.id.audio_list_shuffle).isEnabled());
        assertEquals(View.VISIBLE, root.findViewById(R.id.audio_list_empty_state).getVisibility());
        View add = root.findViewById(R.id.audio_list_import);
        assertEquals(View.VISIBLE, add.getVisibility());
        assertTrue(add.isEnabled());
        add.performClick();

        Intent picker = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertArrayEquals(new String[]{"audio/*"}, picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
    }

    private AudioQueueAdapter adapter(AtomicInteger clicked, AtomicInteger held) {
        return new AudioQueueAdapter(activity, new AudioQueueAdapter.Callback() {
            @Override public void onTrackClick(int index) { clicked.set(index); }
            @Override public void onTrackLongClick(View anchor, int index) { held.set(index); }
        });
    }

    private static MediaItem track(String id, String title, String artist, long duration) {
        Bundle extras = new Bundle();
        extras.putLong(AudioUi.EXTRA_DURATION_MS, duration);
        return new MediaItem.Builder().setMediaId(id).setUri("content://media/synthetic/" + id)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(artist)
                        .setExtras(extras).build()).build();
    }

    public static class SyntheticDocumentProvider extends ContentProvider {
        final AtomicInteger queries = new AtomicInteger();
        volatile boolean queriedOnMain;

        @Override public boolean onCreate() { return true; }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            queries.incrementAndGet();
            queriedOnMain = Looper.myLooper() == Looper.getMainLooper();
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{"synthetic-file.mp3", 98304L});
            return cursor;
        }

        @Override public String getType(Uri uri) { return "audio/mpeg"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
