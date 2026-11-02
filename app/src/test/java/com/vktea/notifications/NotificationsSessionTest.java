package com.vktea.notifications;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Active and retained views use synthetic accounts and a strictly in-memory repository. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationsSessionTest {
    private static FakeRepository repository;
    private ActivityController<Host> host;
    private Context app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-account-a", "101");
        repository = new FakeRepository();
        host = Robolectric.buildActivity(Host.class).setup().visible();
        host.get().getSupportFragmentManager().beginTransaction()
                .add(R.id.content, new TestFragment()).commitNow();
        idle();
        assertEquals(1, repository.requests.size());
    }

    @After public void close() {
        if (host != null) host.close();
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        repository = null;
    }

    @Test public void visibleScreenClearsAccountAAndCancelsItsPageBeforeLoadingB() {
        repository.requests.get(0).success(page("account A", "cursor-A"));
        idle();
        assertEquals(1, list().getAdapter().getItemCount());
        fragment().requireView().findViewById(R.id.notifications_more).performClick();
        assertEquals(2, repository.requests.size());
        Pending oldPage = repository.requests.get(1);
        assertEquals("cursor-A", oldPage.cursor);

        Session.save(app, "synthetic-account-b", "202");
        idle();
        assertTrue(oldPage.canceled);
        assertEquals(0, list().getAdapter().getItemCount());
        assertEquals(3, repository.requests.size());
        assertEquals("", repository.requests.get(2).cursor);
        oldPage.success(page("late account A", "cursor-A2"));
        idle();
        assertEquals(0, list().getAdapter().getItemCount());
        repository.requests.get(2).success(page("account B", "cursor-B"));
        idle();
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals("account B", new ViewModelProvider(fragment()).get(NotificationsViewModel.class)
                .getCurrentState().items.get(0).text);

        Session.clear(app);
        idle();
        assertEquals(0, list().getAdapter().getItemCount());
        assertEquals(3, repository.requests.size());
    }

    @Test public void inactiveScreenClearsOldStateAndLoadsNewAccountOnlyOnReturn() {
        repository.requests.get(0).success(page("account A", "cursor-A"));
        idle();
        host.pause().stop();
        Session.save(app, "synthetic-account-b", "202");
        idle();
        assertEquals(1, repository.requests.size());
        assertTrue(new ViewModelProvider(fragment()).get(NotificationsViewModel.class).getCurrentState().items.isEmpty());
        host.start().resume();
        idle();
        assertEquals(2, repository.requests.size());
        assertEquals("", repository.requests.get(1).cursor);
        assertEquals(0, list().getAdapter().getItemCount());
    }

    private TestFragment fragment() { return (TestFragment) host.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private RecyclerView list() { return fragment().requireView().findViewById(R.id.notifications_list); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static EventPage page(String text, String cursor) {
        return new EventPage(Collections.singletonList(new NotificationEvent(
                "comment_post", 1, "Synthetic", "", text, "", "")), cursor, 0);
    }

    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea); super.onCreate(state);
            FrameLayout content = new FrameLayout(this); content.setId(R.id.content); setContentView(content);
        }
    }
    public static class TestFragment extends NotificationsFragment {
        @Override protected ViewModelProvider.Factory createModelFactory() {
            Context app = requireContext().getApplicationContext();
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new NotificationsViewModel(repository, Session::generation,
                            () -> Session.isAuthorized(app) && Session.getToken(app) != null
                                    && !Session.getToken(app).isEmpty()));
                }
            };
        }
    }
    static final class FakeRepository implements NotificationsRepository {
        final List<Pending> requests = new ArrayList<>();
        @Override public Request load(String cursor, Callback<EventPage> callback) {
            Pending pending = new Pending(cursor, callback); requests.add(pending); return pending;
        }
        @Override public void close() { }
    }
    static final class Pending implements NotificationsRepository.Request {
        final String cursor; final NotificationsRepository.Callback<EventPage> callback;
        boolean canceled;
        Pending(String cursor, NotificationsRepository.Callback<EventPage> callback) { this.cursor = cursor; this.callback = callback; }
        void success(EventPage page) { callback.success(page); }
        @Override public void cancel() { canceled = true; }
    }
}
