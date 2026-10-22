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
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.AuthActivity;
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

/** Regression checks for navigation and rotation without VK authentication or media files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = AudioNavigationTest.ShadowVKApi.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioNavigationTest {
    private static final String OPEN_AUDIO = "com.vktea.action.OPEN_AUDIO";

    private ActivityController<MainActivity> activityController;

    @Before
    public void authorizeSyntheticSession() {
        ShadowVKApi.calls = 0;
        Context context = RuntimeEnvironment.getApplication();
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
    }

    @Test
    public void selectedAudioSectionSurvivesActivityRecreation() {
        launch(null);
        select(R.id.menu_audios, R.drawable.ic_menu_music, R.string.section_audios);
        assertTrue(currentFragment() instanceof AudioFragment);

        activityController.recreate();
        executeNavigation();

        assertTrue(currentFragment() instanceof AudioFragment);
        assertEquals(activity().getString(R.string.section_audios),
                toolbarTitle());
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
    public void audioControlsAreUnavailableWhenSessionConnectionFails() {
        launch(OPEN_AUDIO);
        View root = currentFragment().getView();
        assertNotNull(root);

        // A failed bind must not expose queue actions or playback controls.
        assertFalse(root.findViewById(R.id.audio_open).isEnabled());
        assertFalse(root.findViewById(R.id.audio_clear).isEnabled());
        assertEquals(View.GONE, root.findViewById(R.id.audio_controls).getVisibility());
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
}
