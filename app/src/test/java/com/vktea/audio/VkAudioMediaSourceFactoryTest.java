package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioPage;
import com.vktea.audio.catalog.VkAudioRepository;
import com.vktea.audio.catalog.VkAudioTrack;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises preparation and release with a fake repository and no network/data-source I/O. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkAudioMediaSourceFactoryTest {
    private final FakeRepository repository = new FakeRepository();
    private final RecordingFactory delegate = new RecordingFactory();
    private final AtomicBoolean authorized = new AtomicBoolean(true);
    private final VkAudioMediaSourceFactory factory = new VkAudioMediaSourceFactory(
            delegate, repository, authorized::get);
    private final List<Binding> bindings = new ArrayList<>();

    @After
    public void releasePreparedSources() {
        for (Binding binding : bindings) binding.release();
        repository.close();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void sourceIsLazyAndResolvesOneMatchingFreshHlsUrlOnPreparation() throws Exception {
        MediaItem original = publicTrack();
        MediaSource source = factory.createMediaSource(original);
        assertEquals(0, repository.pending.size());
        assertTrue(delegate.items.isEmpty());
        assertSame(original, source.getMediaItem());

        Binding binding = prepare(source);
        assertEquals(1, repository.pending.size());
        assertEquals(Collections.singletonList("42_73"), repository.pending.get(0).ids);
        assertTrue(delegate.items.isEmpty());

        String fresh = "https://cdn.example.invalid/stream/AUDIO.M3U8?key=synthetic-cdn-marker";
        repository.succeed(0, Arrays.asList(
                resolved(99, 42, "https://cdn.example.invalid/wrong.mp3", true),
                resolved(73, 42, fresh, true)));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, repository.pending.size());
        assertEquals(1, delegate.items.size());
        MediaItem privateChild = delegate.items.get(0);
        assertNotNull(privateChild.localConfiguration);
        assertEquals(fresh, privateChild.localConfiguration.uri.toString());
        assertEquals(MimeTypes.APPLICATION_M3U8, privateChild.localConfiguration.mimeType);
        source.maybeThrowSourceInfoRefreshError();
        assertNotNull(binding.timeline);
        Timeline.Window window = binding.timeline.getWindow(0, new Timeline.Window());
        assertSame(original, window.mediaItem);
        assertNull(window.mediaItem.localConfiguration);
        assertEquals("vk-audio:42_73", window.mediaItem.mediaId);
        assertEquals("Synthetic public title", window.mediaItem.mediaMetadata.title);
        assertNull(window.mediaItem.mediaMetadata.extras);
        assertEquals(60_000, window.getDurationMs());
    }

    @Test
    public void privateAccessIdentityGoesOnlyToRepository() {
        VkAudioMediaSourceFactory withAccess = new VkAudioMediaSourceFactory(delegate, repository,
                authorized::get, null, id -> "42_73_synthetic-private-key");
        MediaSource source = withAccess.createMediaSource(publicTrack());
        Binding binding = prepare(source);
        assertEquals(Collections.singletonList("42_73_synthetic-private-key"), repository.pending.get(0).ids);
        repository.succeed(0, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/track.mp3", true)));
        shadowOf(Looper.getMainLooper()).idle();
        Timeline.Window window = binding.timeline.getWindow(0, new Timeline.Window());
        assertEquals("vk-audio:42_73", window.mediaItem.mediaId);
        assertNull(window.mediaItem.localConfiguration);
        assertNull(window.mediaItem.mediaMetadata.extras);
    }

    @Test
    public void offlineFileIsPreparedWithoutRepositoryAndPublicItemStaysUnchanged() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        com.vktea.auth.Session.save(context, "synthetic-test-token", "42");
        File directory = new File(new File(context.getNoBackupFilesDir(), "offline_audio"), hash("42"));
        assertTrue(directory.mkdirs() || directory.isDirectory());
        File audio = new File(directory, hash("vk-audio:42_73") + ".audio");
        File metadata = new File(directory, hash("vk-audio:42_73") + ".json");
        try {
            try (FileOutputStream out = new FileOutputStream(audio)) { out.write(new byte[]{1, 2, 3}); }
            try (FileOutputStream out = new FileOutputStream(metadata)) { out.write("{}".getBytes(StandardCharsets.UTF_8)); }
            VkAudioMediaSourceFactory withCache = new VkAudioMediaSourceFactory(delegate, repository,
                    authorized::get, context, VkAudioIds::apiId);
            MediaSource source = withCache.createMediaSource(publicTrack());
            Binding binding = prepare(source);
            assertTrue(repository.pending.isEmpty());
            assertEquals("file", delegate.items.get(0).localConfiguration.uri.getScheme());
            Timeline.Window window = binding.timeline.getWindow(0, new Timeline.Window());
            assertEquals("vk-audio:42_73", window.mediaItem.mediaId);
            assertNull(window.mediaItem.localConfiguration);
        } finally {
            audio.delete();
            metadata.delete();
            context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    @Test
    public void mismatchedRestrictedAndUnsafeResolvedRowsNeverReachTheDelegate() {
        List<List<VkAudioTrack>> invalidResults = Arrays.asList(
                Collections.singletonList(resolved(99, 42, "https://cdn.example.invalid/wrong.mp3", true)),
                Collections.singletonList(resolved(73, 42, "https://cdn.example.invalid/restricted.mp3", false)),
                Collections.singletonList(resolved(73, 42, "http://cdn.example.invalid/insecure.mp3", true)),
                Collections.singletonList(resolved(73, 42, "", true)),
                Collections.emptyList());
        for (List<VkAudioTrack> result : invalidResults) {
            MediaSource source = factory.createMediaSource(publicTrack());
            Binding binding = prepare(source);
            repository.succeed(repository.pending.size() - 1, result);
            shadowOf(Looper.getMainLooper()).idle();

            assertSafeFailure(source);
            assertNull(binding.timeline);
            assertTrue(delegate.items.isEmpty());
            binding.release();
        }
    }

    @Test
    public void releaseCancelsResolutionAndDiscardsBothLateCallbackKinds() {
        MediaSource source = factory.createMediaSource(publicTrack());
        Binding binding = prepare(source);

        binding.release();
        assertTrue(repository.pending.get(0).request.canceled);
        // Deliberately violate cancellation in the fake to exercise the source's own guard.
        repository.succeed(0, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/late.mp3", true)));
        repository.fail(0, new VkAudioError(VkAudioError.Kind.API, 201, 0));
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(delegate.items.isEmpty());
        assertNull(binding.timeline);
    }

    @Test
    public void callbacksFromAnEarlierPreparationCannotReplaceTheCurrentSource() throws Exception {
        MediaSource source = factory.createMediaSource(publicTrack());
        Binding old = prepare(source);
        old.release();
        Binding current = prepare(source);
        assertEquals(2, repository.pending.size());

        repository.succeed(0, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/stale.mp3", true)));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(delegate.items.isEmpty());
        assertNull(current.timeline);

        repository.succeed(1, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/current.mp3", true)));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, delegate.items.size());
        assertEquals("/current.mp3", delegate.items.get(0).localConfiguration.uri.getPath());
        assertNotNull(current.timeline);
        source.maybeThrowSourceInfoRefreshError();
    }

    @Test
    public void repositoryErrorsBecomeGenericSourceErrorsWithoutNestedDetails() {
        MediaSource source = factory.createMediaSource(publicTrack());
        Binding binding = prepare(source);

        repository.fail(0, new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED, 5, 401));
        shadowOf(Looper.getMainLooper()).idle();

        assertSafeFailure(source);
        assertNull(binding.timeline);
        assertTrue(delegate.items.isEmpty());
    }

    @Test
    public void revokedAuthorizationBlocksBothNewAndAlreadyPendingResolution() {
        authorized.set(false);
        MediaSource denied = factory.createMediaSource(publicTrack());
        prepare(denied);
        assertTrue(repository.pending.isEmpty());
        assertSafeFailure(denied);

        authorized.set(true);
        MediaSource pending = factory.createMediaSource(publicTrack());
        prepare(pending);
        authorized.set(false);
        repository.succeed(0, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/revoked.mp3", true)));
        shadowOf(Looper.getMainLooper()).idle();

        assertSafeFailure(pending);
        assertTrue(delegate.items.isEmpty());
    }

    @Test
    public void releasingResolvedParentAlsoReleasesItsChild() {
        MediaSource source = factory.createMediaSource(publicTrack());
        Binding binding = prepare(source);
        repository.succeed(0, Collections.singletonList(
                resolved(73, 42, "https://cdn.example.invalid/release.mp3", true)));
        shadowOf(Looper.getMainLooper()).idle();
        FakeSource child = delegate.children.get(0);
        assertEquals(1, child.prepareCount);
        assertEquals(0, child.releaseCount);

        binding.release();

        assertEquals(1, child.releaseCount);
    }

    @Test
    public void nonVkSourcesPassStraightThroughWithoutRepositoryCalls() {
        for (String uri : Arrays.asList("content://synthetic.audio/local",
                "https://cdn.example.invalid/ordinary.mp3")) {
            MediaItem ordinary = new MediaItem.Builder().setUri(uri).build();

            MediaSource actual = factory.createMediaSource(ordinary);

            assertSame(delegate.children.get(delegate.children.size() - 1), actual);
            assertSame(ordinary, actual.getMediaItem());
        }
        assertEquals(2, delegate.items.size());
        assertTrue(repository.pending.isEmpty());
    }

    private static void assertSafeFailure(MediaSource source) {
        IOException error = assertThrows(IOException.class, source::maybeThrowSourceInfoRefreshError);
        assertEquals("VK audio source is unavailable", error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("https://"));
        assertFalse(error.getMessage().contains("vk-audio:"));
    }

    private Binding prepare(MediaSource source) {
        Binding binding = new Binding(source);
        bindings.add(binding);
        source.prepareSource(binding, null, PlayerId.UNSET);
        binding.prepared = true;
        return binding;
    }

    private static MediaItem publicTrack() {
        return new MediaItem.Builder().setMediaId("vk-audio:42_73")
                .setMediaMetadata(new MediaMetadata.Builder().setTitle("Synthetic public title")
                        .setArtist("Synthetic public artist").build()).build();
    }

    private static VkAudioTrack resolved(long id, long owner, String url, boolean available) {
        return new VkAudioTrack(id, owner, "Synthetic resolved artist", "Synthetic resolved title",
                60, url, "synthetic-private-access-key", available);
    }

    private static final class Binding implements MediaSource.MediaSourceCaller {
        final MediaSource source;
        Timeline timeline;
        boolean prepared;

        Binding(MediaSource source) { this.source = source; }

        @Override public void onSourceInfoRefreshed(MediaSource source, Timeline timeline) {
            this.timeline = timeline;
        }

        void release() {
            if (prepared) {
                source.releaseSource(this);
                prepared = false;
            }
        }
    }

    private static final class RecordingFactory implements MediaSource.Factory {
        final List<MediaItem> items = new ArrayList<>();
        final List<FakeSource> children = new ArrayList<>();

        @Override public MediaSource createMediaSource(MediaItem item) {
            items.add(item);
            FakeSource source = new FakeSource(item);
            children.add(source);
            return source;
        }

        @Override public int[] getSupportedTypes() { return new int[]{C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS}; }
        @Override public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider provider) {
            return this;
        }
        @Override public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy policy) {
            return this;
        }
    }

    private static final class FakeSource extends BaseMediaSource {
        final MediaItem item;
        int prepareCount;
        int releaseCount;

        FakeSource(MediaItem item) { this.item = item; }

        @Override public MediaItem getMediaItem() { return item; }
        @Override protected void prepareSourceInternal(TransferListener listener) {
            prepareCount++;
            refreshSourceInfo(new SinglePeriodTimeline(60_000_000L, true, false, false, null, item));
        }
        @Override protected void releaseSourceInternal() { releaseCount++; }
        @Override public void maybeThrowSourceInfoRefreshError() {}
        @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
            throw new AssertionError("Fixture must never open a media period or perform I/O");
        }
        @Override public void releasePeriod(MediaPeriod period) {
            throw new AssertionError("Fixture has no media periods");
        }
    }

    private static final class FakeRepository implements VkAudioRepository {
        final List<Pending> pending = new ArrayList<>();

        @Override public Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback) {
            throw new AssertionError("Playback resolution must use getById");
        }
        @Override public Request getById(List<String> ids, Callback<List<VkAudioTrack>> callback) {
            Pending operation = new Pending(ids, callback);
            pending.add(operation);
            return operation.request;
        }
        void succeed(int index, List<VkAudioTrack> tracks) { pending.get(index).callback.onSuccess(tracks); }
        void fail(int index, VkAudioError error) { pending.get(index).callback.onError(error); }
        @Override public void close() {
            for (Pending operation : pending) operation.request.cancel();
        }
    }

    private static final class Pending {
        final List<String> ids;
        final VkAudioRepository.Callback<List<VkAudioTrack>> callback;
        final FakeRequest request = new FakeRequest();

        Pending(List<String> ids, VkAudioRepository.Callback<List<VkAudioTrack>> callback) {
            this.ids = new ArrayList<>(ids);
            this.callback = callback;
        }
    }

    private static final class FakeRequest implements VkAudioRepository.Request {
        boolean canceled;
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
    }
}
