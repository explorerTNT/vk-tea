package com.vktea.documents;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.menu.MenuEntry;
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
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {DocumentsNavigationTest.Api.class, DocumentsNavigationTest.Factory.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class DocumentsNavigationTest {
    private ActivityController<MainActivity> host;
    @Before public void setup() {
        Context app = RuntimeEnvironment.getApplication(); Session.save(app, "synthetic-documents", "7");
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new ComponentName(app, AudioPlaybackService.class));
    }
    @After public void close() {
        if (host != null) host.close();
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void menuOpensNativeDocumentsAndToolbarSearchFocusesSearch() {
        host = Robolectric.buildActivity(MainActivity.class).setup().visible(); settle();
        host.get().onMenuClick(new MenuEntry(R.id.menu_docs, R.drawable.ic_profile_counter_documents, R.string.section_docs)); settle();
        Fragment current = host.get().getSupportFragmentManager().findFragmentById(R.id.content);
        assertTrue(current instanceof DocumentsFragment);
        RecyclerView rows = current.requireView().findViewById(R.id.documents_list);
        assertEquals(1, rows.getAdapter().getItemCount());
        host.get().findViewById(R.id.toolbar_search).performClick();
        assertTrue(((EditText) current.requireView().findViewById(R.id.documents_search)).hasFocus());
        assertNull(shadowOf(host.get()).getNextStartedActivity());
    }
    private void settle() { host.get().getSupportFragmentManager().executePendingTransactions(); shadowOf(Looper.getMainLooper()).idle(); }
    @Implements(value = DocumentsViewModel.Factory.class, isInAndroidSdk = false)
    public static class Factory {
        @Implementation protected <T extends ViewModel> T create(Class<T> type) {
            DocumentsRepository fake = new DocumentsRepository() {
                @Override public Request load(String query, int offset, Callback callback) {
                    callback.success(new Page(Collections.singletonList(new DocumentItem(1, 7, "Synthetic.pdf", "pdf", 2, "https://vk.com/doc1")), 1, 1));
                    return () -> { };
                }
                @Override public void close() { }
            };
            return type.cast(new DocumentsViewModel(fake));
        }
    }
    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            callback.error(new Exception("Synthetic offline host"));
        }
    }
}
