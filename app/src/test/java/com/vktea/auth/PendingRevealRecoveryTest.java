package com.vktea.auth;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.msg.ChatActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** Synthetic recovery tests; no login or VK transport is performed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PendingRevealRecoveryTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void cleanup() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void restoredOldChatRedirectsBeforeLoadingOrMarkingRead() {
        Session.save(context, "synthetic-old-token", "42");
        Session.saveForLogin(context, "synthetic-new-token", "73", "");
        assertTrue(Session.hasPendingReveal(context));
        assertEquals("73", Session.getUserId(context));
        assertTrue(context.getSharedPreferences("session", Context.MODE_PRIVATE)
                .getBoolean("pending_token_reveal", false));

        Intent oldChat = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_PEER_ID, 123L);
        ActivityController<ChatActivity> controller = Robolectric.buildActivity(ChatActivity.class, oldChat)
                .create(new Bundle());
        try {
            ChatActivity chat = controller.get();
            assertTrue(chat.isFinishing());
            assertNull(chat.findViewById(R.id.chat_root));
            Intent redirect = shadowOf(chat).getNextStartedActivity();
            assertNotNull(redirect);
            assertEquals(TokenRevealActivity.class.getName(), redirect.getComponent().getClassName());
            assertTrue((redirect.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
            assertTrue((redirect.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            assertNull(redirect.getExtras());
            assertTrue(Session.hasPendingReveal(context));
        } finally {
            controller.destroy();
        }
    }

    @Test public void markerRemainsUntilConfirmationAcrossActivityRestoration() {
        Session.saveForLogin(context, "synthetic-new-token", "73", "");
        ActivityController<MainActivity> oldMain = Robolectric.buildActivity(MainActivity.class)
                .create(new Bundle());
        try {
            assertTrue(oldMain.get().isFinishing());
            assertEquals(TokenRevealActivity.class.getName(),
                    shadowOf(oldMain.get()).getNextStartedActivity().getComponent().getClassName());
            assertTrue(Session.hasPendingReveal(context));
        } finally {
            oldMain.destroy();
        }
        ActivityController<TokenRevealActivity> reveal = Robolectric.buildActivity(TokenRevealActivity.class).setup();
        try {
            reveal.recreate();
            assertTrue(Session.hasPendingReveal(context));
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(Session.hasPendingReveal(context));
            assertEquals("73", Session.getUserId(context));
            Intent home = shadowOf(reveal.get()).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(), home.getComponent().getClassName());
        } finally {
            reveal.close();
        }
    }
}
