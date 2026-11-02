package com.vktea;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.documents.DocumentsFragment;
import com.vktea.documents.DocumentsNavigationTest;
import com.vktea.feed.ProfileFragment;
import com.vktea.menu.MenuEntry;
import com.vktea.msg.DialogsFragment;
import com.vktea.ui.AudioFragment;
import com.vktea.ui.AudioNavigationTest;
import com.vktea.updates.UpdateChecker;
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

/** Actual section/back-stack navigation using only synthetic transports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {NavigationTitleRestoreTest.Api.class, NavigationTitleRestoreTest.Updates.class,
        DocumentsNavigationTest.Factory.class, AudioNavigationTest.ShadowAudioCatalogFactory.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationTitleRestoreTest {
    private ActivityController<MainActivity> host;
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "synthetic-navigation", "7");
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new ComponentName(context, AudioPlaybackService.class));
        host = Robolectric.buildActivity(MainActivity.class).setup().visible(); settle();
    }
    @After public void close() {
        host.close(); context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void returningFromProfileRestoresDocumentsAudioAndMessagesTitles() {
        checkSection(R.id.menu_docs, R.drawable.ic_profile_counter_documents, R.string.section_docs, DocumentsFragment.class, R.string.section_docs);
        checkSection(R.id.menu_audios, R.drawable.ic_menu_music, R.string.section_audios, AudioFragment.class, R.string.audio_my_music);
        checkSection(R.id.menu_messages, R.drawable.ic_menu_messages, R.string.section_messages, DialogsFragment.class, R.string.section_messages);
    }
    @Test public void returningToDocumentsAfterChangingMenuRestoresSearchDestination() {
        select(R.id.menu_docs, R.drawable.ic_profile_counter_documents, R.string.section_docs);
        host.get().onMenuHeaderClick(); settle(); assertTrue(current() instanceof ProfileFragment);
        select(R.id.menu_messages, R.drawable.ic_menu_messages, R.string.section_messages);
        assertTrue(host.get().getSupportFragmentManager().popBackStackImmediate()); settle();
        assertTrue(current() instanceof DocumentsFragment);
        assertEquals(host.get().getString(R.string.section_docs), title());
        host.get().findViewById(R.id.toolbar_search).performClick();
        assertTrue(((EditText) current().requireView().findViewById(R.id.documents_search)).hasFocus());
    }
    private void checkSection(int id, int icon, int label, Class<?> type, int expectedTitle) {
        select(id, icon, label); assertTrue(type.isInstance(current()));
        host.get().onMenuHeaderClick(); settle(); assertTrue(current() instanceof ProfileFragment);
        assertTrue(host.get().getSupportFragmentManager().popBackStackImmediate()); settle();
        assertTrue(type.isInstance(current())); assertEquals(host.get().getString(expectedTitle), title());
    }
    private void select(int id, int icon, int title) { host.get().onMenuClick(new MenuEntry(id, icon, title)); settle(); }
    private Fragment current() { return host.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private String title() { return ((TextView) host.get().findViewById(R.id.toolbar_title)).getText().toString(); }
    private void settle() {
        host.get().getSupportFragmentManager().executePendingTransactions();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            callback.error(new Exception("Synthetic offline transport"));
        }
    }
    @Implements(value = UpdateChecker.class, isInAndroidSdk = false)
    public static class Updates {
        @Implementation protected static void check(Context context, UpdateChecker.Callback callback) { callback.onUpToDate(); }
    }
}
