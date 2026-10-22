package com.vktea.audio;

import android.content.Context;
import android.os.Process;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.auth.Session;

import java.util.List;
import java.util.function.BooleanSupplier;

/** System controllers can control playback; only this application supplies media. */
@UnstableApi
final class AudioSessionCallback implements MediaSession.Callback {

    private static final Player.Commands TRANSPORT_COMMANDS = new Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_GET_VOLUME)
            .build();

    private final Context application;
    private final BooleanSupplier serviceAvailable;

    AudioSessionCallback(Context context, BooleanSupplier serviceAvailable) {
        application = context.getApplicationContext();
        this.serviceAvailable = serviceAvailable;
    }

    static boolean ownsController(MediaSession.ControllerInfo controller) {
        // Package names and connection hints are not proof of identity.
        return controller.getUid() == Process.myUid();
    }

    static boolean allowsController(MediaSession.ControllerInfo controller) {
        return ownsController(controller) || controller.isTrusted();
    }

    private boolean isAvailable() {
        return serviceAvailable.getAsBoolean() && Session.isAuthorized(application);
    }

    @Override
    public MediaSession.ConnectionResult onConnect(MediaSession session,
                                                   MediaSession.ControllerInfo controller) {
        if (!isAvailable() || !allowsController(controller)) {
            return MediaSession.ConnectionResult.reject();
        }
        return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(new SessionCommands.Builder().build())
                .setAvailablePlayerCommands(ownsController(controller)
                        ? MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS : TRANSPORT_COMMANDS)
                .build();
    }

    @SuppressWarnings("deprecation") // Enforce current authorization on already connected clients.
    @Override
    public int onPlayerCommandRequest(MediaSession session,
                                      MediaSession.ControllerInfo controller, int playerCommand) {
        if (!isAvailable() || !allowsController(controller)
                || (!ownsController(controller) && !TRANSPORT_COMMANDS.contains(playerCommand))) {
            return SessionError.ERROR_PERMISSION_DENIED;
        }
        return SessionResult.RESULT_SUCCESS;
    }

    @Override
    public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,
                                                            MediaSession.ControllerInfo controller,
                                                            List<MediaItem> mediaItems) {
        // onSetMediaItems also delegates here. Restrict both the advertised
        // commands and their resolver, so a caller cannot smuggle in another URI.
        if (!isAvailable() || !ownsController(controller)) {
            return Futures.immediateFailedFuture(new SecurityException("Queue changes are not allowed"));
        }
        try {
            return Futures.immediateFuture(AudioMediaItems.validate(mediaItems));
        } catch (IllegalArgumentException exception) {
            return Futures.immediateFailedFuture(exception);
        }
    }
}
