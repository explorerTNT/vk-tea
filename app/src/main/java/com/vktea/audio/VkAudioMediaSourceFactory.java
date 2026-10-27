package com.vktea.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import com.vktea.api.VKApi;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioRepository;
import com.vktea.audio.catalog.VkAudioTrack;

import java.io.IOException;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Resolves a VK item when it is prepared, rather than resolving an entire library at once. */
@UnstableApi
final class VkAudioMediaSourceFactory implements MediaSource.Factory {
    private final MediaSource.Factory delegate;
    private final VkAudioRepository repository;
    private final BooleanSupplier authorized;
    @Nullable private final Context application;
    private final Function<String, String> accessIdentity;

    VkAudioMediaSourceFactory(Context context, VkAudioRepository repository, BooleanSupplier authorized) {
        this(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context,
                new DefaultHttpDataSource.Factory().setUserAgent(VKApi.USER_AGENT)
                        .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
                        .setAllowCrossProtocolRedirects(false))), repository, authorized,
                context.getApplicationContext(), id -> AudioSources.apiId(context.getApplicationContext(), id));
    }

    VkAudioMediaSourceFactory(MediaSource.Factory delegate, VkAudioRepository repository,
                             BooleanSupplier authorized) {
        this(delegate, repository, authorized, null, VkAudioIds::apiId);
    }

    VkAudioMediaSourceFactory(MediaSource.Factory delegate, VkAudioRepository repository,
                             BooleanSupplier authorized, @Nullable Context application,
                             Function<String, String> accessIdentity) {
        this.delegate = delegate;
        this.repository = repository;
        this.authorized = authorized;
        this.application = application;
        this.accessIdentity = accessIdentity;
    }

    @Override public MediaSource createMediaSource(MediaItem item) {
        return VkAudioIds.isVk(item) ? new ResolvedSource(item) : delegate.createMediaSource(item);
    }

    @Override public int[] getSupportedTypes() { return delegate.getSupportedTypes(); }

    @Override public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider provider) {
        delegate.setDrmSessionManagerProvider(provider);
        return this;
    }

    @Override public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy policy) {
        delegate.setLoadErrorHandlingPolicy(policy);
        return this;
    }

    private final class ResolvedSource extends CompositeMediaSource<Integer> {
        private final MediaItem original;
        @Nullable private MediaSource child;
        @Nullable private VkAudioRepository.Request request;
        @Nullable private Handler playbackHandler;
        @Nullable private IOException failure;
        private int generation;

        ResolvedSource(MediaItem item) { original = item; }

        @Override public MediaItem getMediaItem() { return original; }

        @Override protected void prepareSourceInternal(@Nullable TransferListener transferListener) {
            super.prepareSourceInternal(transferListener);
            failure = null;
            Handler handler = new Handler(Objects.requireNonNull(Looper.myLooper()));
            playbackHandler = handler;
            int expected = ++generation;
            if (!authorized.getAsBoolean()) { failure = unavailable(); return; }
            File cached = application == null ? null : AudioOfflineCache.findFile(application, original.mediaId);
            if (cached != null) {
                child = delegate.createMediaSource(original.buildUpon().setUri(Uri.fromFile(cached)).build());
                prepareChildSource(0, child);
                return;
            }
            String apiId = Objects.requireNonNull(accessIdentity.apply(original.mediaId));
            request = repository.getById(Collections.singletonList(apiId),
                    new VkAudioRepository.Callback<List<VkAudioTrack>>() {
                        @Override public void onSuccess(List<VkAudioTrack> tracks) {
                            handler.post(() -> {
                                if (expected != generation || playbackHandler != handler) return;
                                request = null;
                                if (!authorized.getAsBoolean()) { failure = unavailable(); return; }
                                VkAudioTrack selected = null;
                                for (VkAudioTrack track : tracks) {
                                    if (original.mediaId.equals(track.mediaId())) { selected = track; break; }
                                }
                                if (selected == null || !selected.hasPlayableUrl()) {
                                    failure = unavailable();
                                    return;
                                }
                                Uri uri = Uri.parse(selected.url);
                                String path = uri.getPath();
                                String mime = path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")
                                        ? MimeTypes.APPLICATION_M3U8 : null;
                                // No account token, signing secret or request headers are sent to the CDN.
                                MediaItem resolved = original.buildUpon().setUri(uri).setMimeType(mime).build();
                                child = delegate.createMediaSource(resolved);
                                prepareChildSource(0, child);
                            });
                        }

                        @Override public void onError(VkAudioError ignored) {
                            handler.post(() -> {
                                if (expected == generation && playbackHandler == handler) {
                                    request = null;
                                    failure = unavailable();
                                }
                            });
                        }
                    });
        }

        @Override public void maybeThrowSourceInfoRefreshError() throws IOException {
            if (failure != null) throw failure;
            super.maybeThrowSourceInfoRefreshError();
        }

        @Override protected void onChildSourceInfoRefreshed(Integer id, MediaSource source, Timeline timeline) {
            // The public queue keeps its stable ID and display metadata, not the expiring signed URL.
            refreshSourceInfo(new ForwardingTimeline(timeline) {
                @Override public Window getWindow(int index, Window window, long projectionUs) {
                    super.getWindow(index, window, projectionUs);
                    window.mediaItem = original;
                    return window;
                }
            });
        }

        @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
            return Objects.requireNonNull(child).createPeriod(id, allocator, startPositionUs);
        }

        @Override public void releasePeriod(MediaPeriod period) { Objects.requireNonNull(child).releasePeriod(period); }

        @Override protected void releaseSourceInternal() {
            generation++;
            if (request != null) request.cancel();
            request = null;
            if (playbackHandler != null) playbackHandler.removeCallbacksAndMessages(null);
            playbackHandler = null;
            super.releaseSourceInternal();
            child = null;
            failure = null;
        }

        private IOException unavailable() { return new IOException("VK audio source is unavailable"); }
    }
}
