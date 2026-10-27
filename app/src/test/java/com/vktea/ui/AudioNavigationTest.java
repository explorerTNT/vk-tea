package com.vktea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioPage;
import com.vktea.audio.catalog.VkAudioRepository;
import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.audio.catalog.VkAudioViewModel;
import com.vktea.auth.AuthActivity;
import com.vktea.auth.Session;
import com.vktea.menu.MenuEntry;
import com.vktea.friends.Friend;
import com.vktea.friends.FriendsError;
import com.vktea.friends.FriendsFragment;
import com.vktea.friends.FriendsRepository;
import com.vktea.friends.FriendsViewModel;
import com.vktea.feed.NewsFeedFragment;
import com.vktea.msg.DialogsFragment;

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
import org.robolectric.shadows.ShadowDialog;

import java.util.List;

/** Regression checks for navigation and rotation without VK authentication or media files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {AudioNavigationTest.ShadowVKApi.class,
        AudioNavigationTest.ShadowAudioCatalogFactory.class, AudioNavigationTest.ShadowFriendsFactory.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioNavigationTest {
    private static final String OPEN_AUDIO = "com.vktea.action.OPEN_AUDIO";

    private ActivityController<MainActivity> activityController;

    @Before
    public void authorizeSyntheticSession() {
        ShadowVKApi.calls = 0;
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "synthetic-navigation-test-token", "1");
        // These tests exercise UI lifecycle rather than Android service binding.
        // Robolectric's default successful bind has no component or session binder.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(context, AudioPlaybackService.class));
    }

    @After
    public void destroyActivity() {
        if (activityController != null) {
            activityController.close();
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("user_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void selectedAudioSectionSurvivesActivityRecreation() {
        launch(null);
        select(R.id.menu_audios, R.drawable.ic_menu_music, R.string.section_audios);
        assertTrue(currentFragment() instanceof AudioFragment);

        activityController.recreate();
        executeNavigation();

        assertTrue(currentFragment() instanceof AudioFragment);
        assertEquals(activity().getString(R.string.audio_list_my_audio),
                toolbarTitle());
    }

    @Test public void configuredStartSectionsOpenRealFragmentsAndInvalidPreferenceFallsBackToNews() {
        String[] sections = {"news", "messages", "friends", "audio"};
        Class<?>[] types = {NewsFeedFragment.class, DialogsFragment.class, FriendsFragment.class, AudioFragment.class};
        Context context = RuntimeEnvironment.getApplication();
        for (int i = 0; i < sections.length; i++) {
            UserSettings.setStartSection(context, sections[i]);
            launch(null);
            assertTrue(sections[i], types[i].isInstance(currentFragment()));
            activityController.close();
            activityController = null;
        }
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().putString("start_section", "placeholder").commit();
        launch(null);
        assertTrue(currentFragment() instanceof NewsFeedFragment);
    }

    @Test public void selectingStartSectionKeepsCurrentPageAndRestorationButAppliesToFreshLaunch() {
        launch(null);
        select(R.id.menu_settings, R.drawable.ic_menu_settings, R.string.section_settings);
        TextView row = findText(currentFragment().getView(), activity().getString(R.string.settings_start_section));
        assertNotNull(row);
        ((View) row.getParent()).performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getListView().performItemClick(dialog.getListView().getChildAt(2), 2, 2);
        assertEquals("friends", UserSettings.startSection(activity()));
        assertTrue(currentFragment() instanceof SettingsFragment);
        activityController.recreate();
        executeNavigation();
        assertTrue(currentFragment() instanceof SettingsFragment);
        activityController.close();
        activityController = null;
        launch(null);
        assertTrue(currentFragment() instanceof FriendsFragment);
    }

    @Test public void explicitAudioIntentOverridesConfiguredStartSection() {
        UserSettings.setStartSection(RuntimeEnvironment.getApplication(), "messages");
        launch(OPEN_AUDIO);
        assertTrue(currentFragment() instanceof AudioFragment);
        assertNull(activity().getIntent().getAction());
    }

    @Test
    public void consumedNotificationActionDoesNotOverrideLaterSectionOnRecreation() {
        launch(OPEN_AUDIO);
        assertTrue(currentFragment() instanceof AudioFragment);
        assertNull(activity().getIntent().getAction());
        select(R.id.menu_settings, R.drawable.ic_menu_settings, R.string.section_settings);

        activityController.recreate();
        executeNavigation();

        assertTrue(currentFragment() instanceof SettingsFragment);
        assertEquals(activity().getString(R.string.section_settings),
                toolbarTitle());
    }

    @Test
    public void notificationDeliveredToExistingActivityOpensAudioAndIsConsumed() {
        launch(null);
        Intent notification = new Intent(activity(), MainActivity.class).setAction(OPEN_AUDIO);

        activityController.newIntent(notification);
        executeNavigation();

        assertTrue(currentFragment() instanceof AudioFragment);
        assertNull(activity().getIntent().getAction());

        select(R.id.menu_settings, R.drawable.ic_menu_settings, R.string.section_settings);
        activityController.recreate();
        executeNavigation();
        assertTrue(currentFragment() instanceof SettingsFragment);
    }

    @Test
    public void failedSessionDisablesShuffleAndCatalogErrorOffersManualRetry() {
        launch(OPEN_AUDIO);
        View root = currentFragment().getView();
        assertNotNull(root);

        // Catalog transport is injected independently from the playback connection.
        assertFalse(root.findViewById(R.id.audio_list_shuffle).isEnabled());
        assertEquals(View.VISIBLE, root.findViewById(R.id.audio_list_retry).getVisibility());
        assertEquals(activity().getString(R.string.audio_list_error_network),
                ((TextView) root.findViewById(R.id.audio_status)).getText().toString());
    }

    @Test
    public void unauthorizedLaunchRedirectsBeforeProfileOrFeedRequest() {
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                .setAction(OPEN_AUDIO);
        activityController = Robolectric.buildActivity(MainActivity.class, intent).create();

        Intent redirect = shadowOf(activity()).getNextStartedActivity();
        assertNotNull(redirect);
        assertNotNull(redirect.getComponent());
        assertEquals(AuthActivity.class.getName(), redirect.getComponent().getClassName());
        assertTrue(activity().isFinishing());
        assertEquals(0, ShadowVKApi.calls);
    }

    private void launch(String action) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class);
        intent.setAction(action);
        activityController = Robolectric.buildActivity(MainActivity.class, intent).setup();
        executeNavigation();
        assertTrue("Profile/feed requests must use the offline API shadow", ShadowVKApi.calls > 0);
    }

    private void select(int id, int icon, int title) {
        activity().onMenuClick(new MenuEntry(id, icon, title));
        executeNavigation();
    }

    private void executeNavigation() {
        activity().getSupportFragmentManager().executePendingTransactions();
    }

    private Fragment currentFragment() {
        return activity().getSupportFragmentManager().findFragmentById(R.id.content);
    }

    private MainActivity activity() {
        return activityController.get();
    }

    private String toolbarTitle() {
        return ((TextView) activity().findViewById(R.id.toolbar_title)).getText().toString();
    }

    private static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) root;
            for (int i = 0; i < parent.getChildCount(); i++) {
                TextView found = findText(parent.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Completes profile/feed loading locally; no transport or image URL is used. */
    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class ShadowVKApi {
        static int calls;

        @Implementation
        protected static void get(Context context, String method, String params,
                                  VKApi.Callback callback) {
            calls++;
            callback.error(new Exception("Synthetic offline navigation test response"));
        }
    }

    /** MainActivity constructs AudioFragment directly, so intercept only its factory seam. */
    @Implements(value = AudioFragment.class, isInAndroidSdk = false)
    public static class ShadowAudioCatalogFactory {
        @Implementation
        protected ViewModelProvider.Factory createCatalogFactory() {
            return new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> type) {
                    return type.cast(new VkAudioViewModel(new OfflineCatalogRepository()));
                }
            };
        }
    }

    @Implements(value = FriendsFragment.class, isInAndroidSdk = false)
    public static class ShadowFriendsFactory {
        @Implementation protected ViewModelProvider.Factory createModelFactory() {
            return new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> type) {
                    return type.cast(new FriendsViewModel(new FriendsRepository() {
                        @Override public Request load(Callback<List<Friend>> callback) {
                            callback.error(new FriendsError(FriendsError.Kind.NETWORK));
                            return () -> {};
                        }
                        @Override public void close() { }
                    }));
                }
            };
        }
    }

    static class OfflineCatalogRepository implements VkAudioRepository {
        @Override public Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback) {
            callback.onError(new VkAudioError(VkAudioError.Kind.NETWORK));
            return new Request() {
                private boolean canceled;
                @Override public void cancel() { canceled = true; }
                @Override public boolean isCanceled() { return canceled; }
            };
        }
        @Override public Request getById(List<String> ids, Callback<List<VkAudioTrack>> callback) {
            throw new AssertionError("Navigation must never request VK play URLs");
        }
        @Override public void close() {}
    }
}
