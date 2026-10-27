package com.vktea.notifications;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main menu integration uses only synthetic account data and an in-memory repository. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {NotificationsNavigationTest.Api.class,
        NotificationsNavigationTest.Factory.class, NotificationsNavigationTest.NoLiveTransport.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationsNavigationTest {
    private ActivityController<MainActivity> host;
    @Before public void setup() {
        Context app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-notifications-navigation", "1");
        Factory.created.clear();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new ComponentName(app, AudioPlaybackService.class));
    }
    @After public void close() {
        if (host != null) host.close();
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void menuOpensNotificationsAndRecreationRetainsLoadedPage() {
        host = Robolectric.buildActivity(MainActivity.class).setup().visible(); settle();
        host.get().onMenuClick(new MenuEntry(R.id.menu_feedback, R.drawable.ic_menu_notifications, R.string.section_feedback));
        settle();
        assertTrue(current() instanceof NotificationsFragment);
        assertEquals(host.get().getString(R.string.section_feedback), title());
        assertEquals(1, rows()); assertEquals(1, Factory.created.size()); assertEquals(1, Factory.created.get(0).loads);
        host.recreate(); settle();
        assertTrue(current() instanceof NotificationsFragment);
        assertEquals(host.get().getString(R.string.section_feedback), title());
        assertEquals(1, rows()); assertEquals(1, Factory.created.size()); assertEquals(1, Factory.created.get(0).loads);
    }
    private Fragment current() { return host.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private int rows() { return ((RecyclerView) current().requireView().findViewById(R.id.notifications_list)).getAdapter().getItemCount(); }
    private String title() { return ((TextView) host.get().findViewById(R.id.toolbar_title)).getText().toString(); }
    private void settle() { host.get().getSupportFragmentManager().executePendingTransactions(); shadowOf(Looper.getMainLooper()).idle(); }

    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            callback.error(new Exception("Synthetic offline response"));
        }
    }
    @Implements(value = NotificationsViewModel.Factory.class, isInAndroidSdk = false)
    public static class Factory {
        static final List<FakeRepository> created = new ArrayList<>();
        @Implementation protected <T extends ViewModel> T create(Class<T> type) {
            FakeRepository repository = new FakeRepository(); created.add(repository);
            return type.cast(new NotificationsViewModel(repository));
        }
    }
    @Implements(value = VkNotificationsRepository.class, isInAndroidSdk = false)
    public static class NoLiveTransport {
        @Implementation protected NotificationsRepository.Request load(String cursor, NotificationsRepository.Callback<EventPage> callback) {
            throw new AssertionError("Live notification transport forbidden in navigation tests");
        }
    }
    static final class FakeRepository implements NotificationsRepository {
        int loads;
        @Override public Request load(String cursor, Callback<EventPage> callback) {
            loads++;
            callback.success(new EventPage(Collections.singletonList(new NotificationEvent(
                    "comment_post", 1, "Автор", "", "Ответ", "", "")), "", 0));
            return () -> { };
        }
        @Override public void close() { }
    }
}
