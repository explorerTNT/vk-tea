package com.vktea.friends;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.feed.ProfileFragment;
import com.vktea.menu.MenuEntry;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real MainActivity navigation with synthetic friends and no account or image transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {FriendsNavigationTest.ShadowVKApi.class,
        FriendsNavigationTest.ShadowFriendsFactory.class,
        FriendsNavigationTest.ShadowNoLiveFriendsTransport.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class FriendsNavigationTest {
    private ActivityController<MainActivity> activityController;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "synthetic-friends-navigation-session", "1");
        ShadowVKApi.methods.clear();
        ShadowFriendsFactory.created.clear();
        // The global mini player is outside this directory/navigation test.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(context, AudioPlaybackService.class));
    }

    @After public void close() {
        if (activityController != null) activityController.close();
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void mainFriendsMenuCreatesNativePanelAndRestoresItWithoutAnotherLoad() {
        launchFriends();
        assertEquals(activity().getString(R.string.section_friends), toolbarTitle());
        assertEquals(2, rows().getItemCount());
        assertEquals(2, ((TabLayout) currentFragment().requireView()
                .findViewById(R.id.friends_tabs)).getTabCount());
        assertEquals(1, repository().loads);

        activityController.recreate();
        executeNavigation();

        assertTrue(currentFragment() instanceof FriendsFragment);
        assertEquals(activity().getString(R.string.section_friends), toolbarTitle());
        assertEquals(1, ShadowFriendsFactory.created.size());
        assertEquals(1, repository().loads);
        assertEquals(2, rows().getItemCount());
    }

    @Test public void toolbarSearchAndDropdownOperateOnFriendsInsteadOfNews() {
        launchFriends();
        activity().findViewById(R.id.toolbar_search).performClick();
        EditText search = currentFragment().requireView().findViewById(R.id.friends_search);
        assertTrue(search.hasFocus());
        search.setText("Анна");
        assertEquals(1, rows().getItemCount());
        currentFragment().requireView().findViewById(R.id.friends_clear_search).performClick();
        assertEquals("", search.getText().toString());
        assertEquals(2, rows().getItemCount());

        activity().findViewById(R.id.toolbar_title_btn).performClick();
        PopupMenu menu = ReflectionHelpers.getField(currentFragment(), "menu");
        assertNotNull("Main must route its dropdown to FriendsFragment", menu);
        assertTrue(menu.getMenu().performIdentifierAction(2, 0));
        assertEquals(1, ((TabLayout) currentFragment().requireView()
                .findViewById(R.id.friends_tabs)).getSelectedTabPosition());
        assertEquals(1, rows().getItemCount());
        assertEquals(1, repository().loads);
        assertTrue(menu.getMenu().performIdentifierAction(3, 0));
        assertEquals(2, repository().loads);
        assertTrue(currentFragment() instanceof FriendsFragment);
        assertNull(shadowOf(activity()).getNextStartedActivity());
    }

    @Test public void friendRowOpensNativeProfileAndBackRestoresFriendsTitleAndSnapshot() {
        launchFriends();
        Friend expected = repository().friends.get(0);
        row(0).itemView.performClick();
        executeNavigation();

        assertTrue(currentFragment() instanceof ProfileFragment);
        assertEquals(expected.id, currentFragment().requireArguments().getLong("user_id"));
        assertEquals(activity().getString(R.string.profile_title), toolbarTitle());
        assertEquals(1, activity().getSupportFragmentManager().getBackStackEntryCount());
        assertTrue(activity().getSupportFragmentManager().popBackStackImmediate());
        executeNavigation();

        assertTrue(currentFragment() instanceof FriendsFragment);
        assertEquals(activity().getString(R.string.section_friends), toolbarTitle());
        assertEquals(2, rows().getItemCount());
        assertEquals(1, repository().loads);
    }

    @Test public void profileRecreationKeepsProfileTitleThenReturnsToFriends() {
        launchFriends();
        row(0).itemView.performClick();
        executeNavigation();
        activityController.recreate();
        executeNavigation();

        assertTrue(currentFragment() instanceof ProfileFragment);
        assertEquals(activity().getString(R.string.profile_title), toolbarTitle());
        assertTrue(activity().getSupportFragmentManager().popBackStackImmediate());
        executeNavigation();
        assertTrue(currentFragment() instanceof FriendsFragment);
        assertEquals(activity().getString(R.string.section_friends), toolbarTitle());
        assertEquals(1, ShadowFriendsFactory.created.size());
        assertEquals(1, repository().loads);
    }

    @Test public void messageButtonOpensTheChosenChatWithoutSendingOrOpeningAProfile() {
        launchFriends();
        Friend expected = repository().friends.get(0);
        int requestsBeforeClick = ShadowVKApi.methods.size();
        row(0).message.performClick();

        Intent opened = shadowOf(activity()).getNextStartedActivity();
        assertNotNull(opened);
        assertNotNull(opened.getComponent());
        assertEquals(ChatActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals(expected.id, opened.getLongExtra(ChatActivity.EXTRA_PEER_ID, 0));
        assertEquals(expected.name, opened.getStringExtra(ChatActivity.EXTRA_TITLE));
        assertEquals(expected.photo, opened.getStringExtra(ChatActivity.EXTRA_PHOTO));
        assertEquals(requestsBeforeClick, ShadowVKApi.methods.size());
        assertTrue(currentFragment() instanceof FriendsFragment);
        assertEquals(0, activity().getSupportFragmentManager().getBackStackEntryCount());
        assertEquals(activity().getString(R.string.section_friends), toolbarTitle());
        assertNull(shadowOf(activity()).getNextStartedActivity());
    }

    private void launchFriends() {
        activityController = Robolectric.buildActivity(MainActivity.class).setup();
        executeNavigation();
        activity().onMenuClick(new MenuEntry(R.id.menu_friends, R.drawable.ic_menu_friends,
                R.string.section_friends));
        executeNavigation();
        assertTrue(currentFragment() instanceof FriendsFragment);
        assertEquals("Friends loading must use the offline repository factory", 1,
                ShadowFriendsFactory.created.size());
        assertFalse("Main profile/news requests must use the offline API shadow", ShadowVKApi.methods.isEmpty());
    }

    private void executeNavigation() { activity().getSupportFragmentManager().executePendingTransactions(); }
    private MainActivity activity() { return activityController.get(); }
    private Fragment currentFragment() { return activity().getSupportFragmentManager().findFragmentById(R.id.content); }
    private String toolbarTitle() { return ((TextView) activity().findViewById(R.id.toolbar_title)).getText().toString(); }
    private FakeFriendsRepository repository() { return ShadowFriendsFactory.created.get(0); }
    private FriendsAdapter rows() {
        RecyclerView list = currentFragment().requireView().findViewById(R.id.friends_list);
        return (FriendsAdapter) list.getAdapter();
    }
    private FriendsAdapter.Holder row(int position) {
        RecyclerView list = currentFragment().requireView().findViewById(R.id.friends_list);
        FriendsAdapter adapter = rows();
        FriendsAdapter.Holder holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(position));
        adapter.onBindViewHolder(holder, position);
        return holder;
    }

    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class ShadowVKApi {
        static final List<String> methods = new ArrayList<>();
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            methods.add(method);
            if (method.equals("messages.send") || method.equals("messages.markAsRead")
                    || method.equals("friends.add") || method.equals("friends.delete"))
                throw new AssertionError("Directory navigation must not mutate account state");
            callback.error(new Exception("Synthetic offline navigation response"));
        }
    }

    @Implements(value = FriendsViewModel.Factory.class, isInAndroidSdk = false)
    public static class ShadowFriendsFactory {
        static final List<FakeFriendsRepository> created = new ArrayList<>();
        @Implementation protected <T extends ViewModel> T create(Class<T> type) {
            FakeFriendsRepository repository = new FakeFriendsRepository(RuntimeEnvironment.getApplication());
            created.add(repository);
            return type.cast(new FriendsViewModel(repository));
        }
    }

    /** Fail closed if the ViewModel factory seam stops being instrumented. */
    @Implements(value = VkFriendsRepository.class, isInAndroidSdk = false)
    public static class ShadowNoLiveFriendsTransport {
        @Implementation protected FriendsRepository.Request load(FriendsRepository.Callback<List<Friend>> callback) {
            throw new AssertionError("Production friends transport is forbidden in navigation tests");
        }
    }

    static final class FakeFriendsRepository implements FriendsRepository {
        final List<Friend> friends;
        int loads;
        FakeFriendsRepository(Context context) {
            String photo = "android.resource://" + context.getPackageName() + "/" + R.drawable.chat_avatar_placeholder;
            friends = Arrays.asList(new Friend(101, "Анна", "Тестовая", photo, true, true, 0, 0, ""),
                    new Friend(102, "Борис", "Пример", "", false, false, 0, 0, ""));
        }
        @Override public Request load(Callback<List<Friend>> callback) {
            loads++;
            callback.success(friends);
            return () -> { };
        }
        @Override public void close() { }
    }
}
