package com.vktea.audio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.auth.Session;

/** The UI boundary for the single background music player. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class AudioPlayback {

    public static final String ACTION_OPEN_AUDIO = "com.vktea.action.OPEN_AUDIO";
    static final String ACTION_STOP = "com.vktea.audio.action.STOP";

    private AudioPlayback() {}

    /**
     * Connects a main-thread controller without giving an Activity to the service.
     * The owner must call MediaController.releaseFuture when its UI stops, including
     * when this future has not completed yet. Releasing a controller keeps active
     * background playback running.
     */
    public static ListenableFuture<MediaController> connect(Context context) {
        return connect(context, null);
    }

    /** Connects with lifecycle notifications, including service-side logout. */
    public static ListenableFuture<MediaController> connect(Context context,
                                                           @Nullable MediaController.Listener listener) {
        Context application = context.getApplicationContext();
        if (!Session.isAuthorized(application)) {
            return Futures.immediateFailedFuture(
                    new SecurityException("Audio playback requires an active session"));
        }
        SessionToken token = new SessionToken(application,
                new ComponentName(application, AudioPlaybackService.class));
        MediaController.Builder builder = new MediaController.Builder(application, token)
                .setApplicationLooper(Looper.getMainLooper());
        if (listener != null) {
            builder.setListener(listener);
        }
        return builder.buildAsync();
    }

    /**
     * Stops and clears an existing player, even while a UI controller is bound.
     * Delivery happens on the service main thread. This never starts an absent
     * service. The receiver is not exported, so other apps cannot send this action.
     */
    public static void stop(Context context) {
        Context application = context.getApplicationContext();
        application.sendBroadcast(new Intent(ACTION_STOP).setPackage(application.getPackageName()));
    }
}
