package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.List;

/** Offline compatibility checks for legacy sessions and sessions with an API signing secret. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionTest {
    private Context context;

    @Before
    public void clearStoredSession() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void removeSyntheticSession() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void savesTokenUserAndSigningSecretTogether() {
        Session.save(context, "synthetic-signed-token", "42", "synthetic-signing-secret");

        assertTrue(Session.isAuthorized(context));
        assertEquals("synthetic-signed-token", Session.getToken(context));
        assertEquals("42", Session.getUserId(context));
        assertEquals("synthetic-signing-secret", Session.getSecret(context));
    }

    @Test
    public void legacySaveDoesNotReusePreviousSigningSecret() {
        Session.save(context, "synthetic-first-token", "42", "synthetic-first-secret");

        Session.save(context, "synthetic-legacy-token", "73");

        assertTrue(Session.isAuthorized(context));
        assertEquals("synthetic-legacy-token", Session.getToken(context));
        assertEquals("73", Session.getUserId(context));
        assertEquals("", Session.getSecret(context));
    }

    @Test
    public void clearRemovesEveryCredentialAndRequestsPlaybackStop() {
        Session.save(context, "synthetic-clear-token", "42", "synthetic-clear-secret");
        List<Intent> before = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        int previousBroadcastCount = before.size();

        Session.clear(context);

        assertFalse(Session.isAuthorized(context));
        assertNull(Session.getToken(context));
        assertNull(Session.getUserId(context));
        assertNull(Session.getSecret(context));
        List<Intent> broadcasts = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        boolean stopRequested = false;
        for (int i = previousBroadcastCount; i < broadcasts.size(); i++) {
            Intent broadcast = broadcasts.get(i);
            if ("com.vktea.audio.action.STOP".equals(broadcast.getAction())
                    && context.getPackageName().equals(broadcast.getPackage())) {
                stopRequested = true;
            }
        }
        assertTrue("Signing out must request playback stop within this app", stopRequested);
    }
}
