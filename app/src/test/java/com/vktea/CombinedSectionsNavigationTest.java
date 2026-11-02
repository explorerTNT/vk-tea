package com.vktea;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.documents.DocumentsFragment;
import com.vktea.documents.DocumentsNavigationTest;
import com.vktea.documents.DocumentsRepository;
import com.vktea.menu.MenuEntry;
import com.vktea.msg.ChatActivity;
import com.vktea.msg.DialogsFragment;
import com.vktea.notifications.NotificationsFragment;
import com.vktea.notifications.NotificationsNavigationTest;
import com.vktea.updates.UpdateChecker;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Covers the merged menu with in-memory fixtures; no external transport is permitted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {CombinedSectionsNavigationTest.Api.class,
        CombinedSectionsNavigationTest.Updates.class,
        CombinedSectionsNavigationTest.NoLiveDocuments.class,
        NotificationsNavigationTest.Factory.class,
        NotificationsNavigationTest.NoLiveTransport.class,
        DocumentsNavigationTest.Factory.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class CombinedSectionsNavigationTest {
    private ActivityController<MainActivity> host;
    private ActivityController<ChatActivity> chat;
    private Context app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-combined-sections", "1");
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(app, AudioPlaybackService.class));
        host = Robolectric.buildActivity(MainActivity.class).setup().visible();
        settle();
    }

    @After public void close() {
        if (chat != null) chat.close();
        if (host != null) host.close();
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test public void eventsDocumentsAndVoiceChatRemainAccessibleInSequence() {
        open(R.id.menu_feedback, R.drawable.ic_menu_notifications, R.string.section_feedback);
        assertTrue(current() instanceof NotificationsFragment);
        assertEquals(host.get().getString(R.string.section_feedback), title());
        assertEquals(1, ((RecyclerView) current().requireView().findViewById(R.id.notifications_list))
                .getAdapter().getItemCount());

        open(R.id.menu_docs, R.drawable.ic_profile_counter_documents, R.string.section_docs);
        assertTrue(current() instanceof DocumentsFragment);
        assertEquals(host.get().getString(R.string.section_docs), title());
        assertEquals(1, ((RecyclerView) current().requireView().findViewById(R.id.documents_list))
                .getAdapter().getItemCount());
        host.get().findViewById(R.id.toolbar_search).performClick();
        assertTrue(((EditText) current().requireView().findViewById(R.id.documents_search)).hasFocus());

        open(R.id.menu_feedback, R.drawable.ic_menu_notifications, R.string.section_feedback);
        assertTrue(current() instanceof NotificationsFragment);
        assertEquals(host.get().getString(R.string.section_feedback), title());

        open(R.id.menu_messages, R.drawable.ic_menu_messages, R.string.section_messages);
        assertTrue(current() instanceof DialogsFragment);
        RecyclerView dialogs = current().requireView().findViewById(R.id.dialogs_list);
        RecyclerView.Adapter dialogsAdapter = dialogs.getAdapter();
        assertEquals(1, dialogsAdapter.getItemCount());
        RecyclerView.ViewHolder dialog = dialogsAdapter.onCreateViewHolder(dialogs, dialogsAdapter.getItemViewType(0));
        dialogsAdapter.onBindViewHolder(dialog, 0);
        assertTrue(dialog.itemView.performClick());
        Intent intent = shadowOf(host.get()).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(ChatActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(42, intent.getLongExtra(ChatActivity.EXTRA_PEER_ID, 0));

        chat = Robolectric.buildActivity(ChatActivity.class, intent).setup().visible();
        idle();
        RecyclerView messages = chat.get().findViewById(R.id.chat_list);
        RecyclerView.Adapter adapter = messages.getAdapter();
        assertEquals(1, adapter.getItemCount());
        RecyclerView.ViewHolder message = adapter.onCreateViewHolder(messages, adapter.getItemViewType(0));
        adapter.onBindViewHolder(message, 0);
        assertNotNull(message.itemView.findViewById(R.id.chat_voice_play_icon));
        assertNotNull(message.itemView.findViewById(R.id.chat_voice_progress));
    }

    private void open(int id, int icon, int title) {
        host.get().onMenuClick(new MenuEntry(id, icon, title));
        settle();
    }
    private Fragment current() { return host.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private String title() { return ((TextView) host.get().findViewById(R.id.toolbar_title)).getText().toString(); }
    private void settle() { host.get().getSupportFragmentManager().executePendingTransactions(); idle(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            try {
                if ("messages.getDialogs".equals(method)) {
                    callback.success(new JSONObject("{\"response\":{\"items\":[{\"conversation\":{\"peer\":{\"id\":42}}," +
                            "\"last_message\":{\"peer_id\":42,\"text\":\"Synthetic\",\"date\":1}}]}}"));
                } else if ("messages.getHistory".equals(method)) {
                    callback.success(new JSONObject("{\"response\":{\"items\":[{\"id\":17,\"from_id\":1,\"out\":1,\"date\":1," +
                            "\"attachments\":[{\"type\":\"audio_message\",\"audio_message\":{\"duration\":5," +
                            "\"link_ogg\":\"https://example.invalid/voice.ogg\",\"waveform\":[1,5,20]}}]}]}}"));
                } else {
                    callback.error(new Exception("Synthetic offline transport"));
                }
            } catch (org.json.JSONException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    @Implements(value = UpdateChecker.class, isInAndroidSdk = false)
    public static class Updates {
        @Implementation protected static void check(Context context, UpdateChecker.Callback callback) {
            callback.onUpToDate();
        }
    }

    @Implements(className = "com.vktea.documents.VkDocumentsRepository", isInAndroidSdk = false)
    public static class NoLiveDocuments {
        @Implementation protected DocumentsRepository.Request load(String query, int offset, DocumentsRepository.Callback callback) {
            throw new AssertionError("Live documents transport forbidden");
        }
    }
}
