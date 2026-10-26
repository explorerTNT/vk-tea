package com.vktea;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.vktea.auth.AuthActivity;
import com.vktea.auth.Session;
import com.vktea.menu.LeftMenuAdapter;
import com.vktea.menu.MenuEntry;
import com.vktea.ui.PlaceholderFragment;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MenuEntry.Callback {

    private static final String STATE_SECTION = "current_section";
    private static final String ACTION_OPEN_AUDIO = "com.vktea.action.OPEN_AUDIO";

    private DrawerLayout drawer;
    private RecyclerView menuView;
    private LeftMenuAdapter menuAdapter;
    private List<MenuEntry> menu;
    private int currentId;
    private long feedListId;
    private TextView toolbarTitle;
    private TextView toolbarBadge;
    private com.vktea.ui.AudioMiniPlayerView miniPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isAuthorized(this)) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);

        menu = buildMenu();

        drawer = findViewById(R.id.drawer);
        View burger = findViewById(R.id.toolbar_burger);
        toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarBadge = findViewById(R.id.toolbar_badge);
        miniPlayer = findViewById(R.id.audio_mini_player);

        burger.setOnClickListener(v -> drawer.openDrawer(menuView));

        View titleBtn = findViewById(R.id.toolbar_title_btn);
        titleBtn.setOnClickListener(this::showSectionDropdown);

        findViewById(R.id.toolbar_search).setOnClickListener(v -> {
            androidx.fragment.app.Fragment current = getSupportFragmentManager().findFragmentById(R.id.content);
            if (currentId == R.id.menu_audios && current instanceof com.vktea.ui.AudioFragment) {
                ((com.vktea.ui.AudioFragment) current).showAudioSearch();
            } else if (current instanceof com.vktea.friends.FriendsFragment) {
                ((com.vktea.friends.FriendsFragment) current).showFriendsSearch();
            } else if (current instanceof com.vktea.groups.GroupsFragment) {
                ((com.vktea.groups.GroupsFragment) current).showGroupsSearch();
            } else Toast.makeText(this, R.string.section_search, Toast.LENGTH_SHORT).show();
        });
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateContentTitle);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, null, 0, 0) {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                super.onDrawerSlide(drawerView, Math.min(slideOffset, 0.02f));
            }
        };
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        menuView = findViewById(R.id.left_menu);
        menuView.setLayoutManager(new LinearLayoutManager(this));

        currentId = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SECTION, R.id.menu_news) : R.id.menu_news;
        menuAdapter = new LeftMenuAdapter(menu, this, currentId);
        menuView.setAdapter(menuAdapter);
        ViewCompat.setOnApplyWindowInsetsListener(menuView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            menuAdapter.setSystemInsets(bars.top, bars.bottom);
            return insets;
        });

        toolbarTitle.setText(getString(R.string.section_news));

        loadUserFeedLists();

        com.vktea.api.VKApi.get(this, "users.get", "fields=photo_200,status,counters,cover", new com.vktea.api.VKApi.Callback() {
            @Override
            public void success(org.json.JSONObject response) {
                try {
                    org.json.JSONObject u = response.getJSONArray("response").getJSONObject(0);
                    com.vktea.api.User.Counters counters = null;
                    if (u.has("counters")) {
                        org.json.JSONObject c = u.getJSONObject("counters");
                        counters = new com.vktea.api.User.Counters(
                                c.optInt("friends", 0),
                                c.optInt("messages", 0),
                                c.optInt("notifications", 0),
                                c.optInt("groups", 0));
                    }
                    String coverUrl = null;
                    org.json.JSONObject cover = u.optJSONObject("cover");
                    if (cover != null && cover.optInt("enabled", 0) == 1) {
                        org.json.JSONArray images = cover.optJSONArray("images");
                        if (images != null) {
                            for (int i = 0; i < images.length(); i++) {
                                org.json.JSONObject img = images.optJSONObject(i);
                                if (img != null && "o".equals(img.optString("type", ""))) {
                                    coverUrl = img.optString("url", null);
                                }
                            }
                            if (coverUrl == null && images.length() > 0) {
                                coverUrl = images.optJSONObject(images.length() - 1).optString("url", null);
                            }
                        }
                    }
                    final com.vktea.api.User user = new com.vktea.api.User(
                            u.getLong("id"),
                            u.optString("first_name", ""),
                            u.optString("last_name", ""),
                            u.optString("photo_200", null),
                            u.optString("status", ""),
                            counters,
                            coverUrl);
                    runOnUiThread(() -> {
                        menuAdapter.setHeaderUser(user);
                        if (user.counters != null) {
                            int red = user.counters.messages + user.counters.notifications;
                            if (red > 0) {
                                toolbarBadge.setVisibility(View.VISIBLE);
                                toolbarBadge.setText(String.valueOf(red));
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override
            public void error(Exception e) {
            }
        });

        if (ACTION_OPEN_AUDIO.equals(getIntent().getAction())) {
            getIntent().setAction(null);
            showSection(R.id.menu_audios);
        } else if (savedInstanceState == null) {
            showSection(currentId);
        } else {
            for (MenuEntry entry : menu) {
                if (entry.id == currentId) {
                    toolbarTitle.setText(currentId == R.id.menu_audios ? R.string.audio_my_music : entry.titleRes);
                    break;
                }
            }
        }
        toolbarTitle.setTextSize(currentId == R.id.menu_audios ? 20 : 23);
        updateContentTitle();
    }

    private void updateContentTitle() {
        androidx.fragment.app.Fragment current = getSupportFragmentManager().findFragmentById(R.id.content);
        int title;
        if (current instanceof com.vktea.feed.ProfileFragment) title = ((com.vktea.feed.ProfileFragment) current).navigationTitleRes();
        else if (current instanceof com.vktea.friends.FriendsFragment) title = R.string.section_friends;
        else if (current instanceof com.vktea.groups.GroupsFragment) title = R.string.section_groups;
        else return;
        toolbarTitle.setText(title);
        toolbarTitle.setTextSize(23);
    }

    @Override protected void onStart() {
        super.onStart();
        if (miniPlayer != null) miniPlayer.start();
    }

    @Override protected void onStop() {
        if (miniPlayer != null) miniPlayer.stop();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (menu != null && ACTION_OPEN_AUDIO.equals(intent.getAction())) {
            intent.setAction(null);
            showSection(R.id.menu_audios);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SECTION, currentId);
        super.onSaveInstanceState(outState);
    }

    private void showSectionDropdown(View anchor) {
        androidx.fragment.app.Fragment current = getSupportFragmentManager().findFragmentById(R.id.content);
        if (currentId == R.id.menu_audios && current instanceof com.vktea.ui.AudioFragment) {
            ((com.vktea.ui.AudioFragment) current).showAudioMenu(anchor);
            return;
        }
        if (current instanceof com.vktea.friends.FriendsFragment) {
            ((com.vktea.friends.FriendsFragment) current).showFriendsMenu(anchor);
            return;
        }
        if (current instanceof com.vktea.groups.GroupsFragment) {
            ((com.vktea.groups.GroupsFragment) current).showGroupsMenu(anchor);
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 0, 0, R.string.list_newsfeed);
        popup.getMenu().add(0, -1, 1, R.string.list_recommendations);
        popup.getMenu().add(0, -2, 2, R.string.list_friends);
        popup.getMenu().add(0, -4, 3, R.string.list_photos);
        popup.getMenu().add(0, -5, 4, R.string.list_videos);
        int order = 5;
        for (int i = 0; i < userFeedLists.size(); i++) {
            popup.getMenu().add(0, userFeedLists.keyAt(i), order++, userFeedLists.valueAt(i));
        }
        popup.setOnMenuItemClickListener(item -> {
            feedListId = item.getItemId();
            toolbarTitle.setText(item.getTitle());
            showSection(R.id.menu_news);
            return true;
        });
        popup.show();
    }

    private final android.util.SparseArray<String> userFeedLists = new android.util.SparseArray<>();

    private void loadUserFeedLists() {
        com.vktea.api.VKApi.get(this, "newsfeed.getLists", "", new com.vktea.api.VKApi.Callback() {
            @Override
            public void success(org.json.JSONObject response) {
                try {
                    org.json.JSONArray items = response.getJSONObject("response").getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        org.json.JSONObject l = items.getJSONObject(i);
                        userFeedLists.put((int) l.getLong("id"), l.optString("title", ""));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void error(Exception e) {
            }
        });
    }

    private List<MenuEntry> buildMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(R.id.menu_news, R.drawable.ic_menu_news, R.string.section_news));
        entries.add(new MenuEntry(R.id.menu_feedback, R.drawable.ic_menu_notifications, R.string.section_feedback));
        entries.add(new MenuEntry(R.id.menu_messages, R.drawable.ic_menu_messages, R.string.section_messages));
        entries.add(new MenuEntry(R.id.menu_friends, R.drawable.ic_menu_friends, R.string.section_friends));
        entries.add(new MenuEntry(R.id.menu_groups, R.drawable.ic_menu_communities, R.string.section_groups));
        entries.add(new MenuEntry(R.id.menu_photos, R.drawable.ic_menu_photos, R.string.section_photos));
        entries.add(new MenuEntry(R.id.menu_videos, R.drawable.ic_menu_videos, R.string.section_videos));
        entries.add(new MenuEntry(R.id.menu_audios, R.drawable.ic_menu_music, R.string.section_audios));
        entries.add(new MenuEntry(R.id.menu_games, R.drawable.ic_menu_games, R.string.section_games));
        entries.add(new MenuEntry(R.id.menu_fave, R.drawable.ic_menu_bookmarks, R.string.section_fave));
        entries.add(new MenuEntry(R.id.menu_search, R.drawable.ic_menu_global_search, R.string.section_search));
        entries.add(new MenuEntry(R.id.menu_docs, R.drawable.ic_profile_counter_documents, R.string.section_docs));
        entries.add(new MenuEntry(R.id.menu_settings, R.drawable.ic_menu_settings, R.string.section_settings));
        return entries;
    }

    @Override
    public void onMenuClick(MenuEntry entry) {
        drawer.closeDrawer(menuView);
        showSection(entry.id);
    }

    private void showSection(int menuId) {
        for (MenuEntry entry : menu) {
            if (entry.id == menuId) {
                currentId = menuId;
                toolbarTitle.setText(menuId == R.id.menu_audios ? R.string.audio_my_music : entry.titleRes);
                toolbarTitle.setTextSize(menuId == R.id.menu_audios ? 20 : 23);
                menuAdapter.setSelectedId(menuId);
                androidx.fragment.app.Fragment fragment;
                if (menuId == R.id.menu_settings) {
                    fragment = new com.vktea.ui.SettingsFragment();
                } else if (menuId == R.id.menu_news) {
                    fragment = com.vktea.feed.NewsFeedFragment.newInstance(feedListId);
                } else if (menuId == R.id.menu_messages) {
                    fragment = new com.vktea.msg.DialogsFragment();
                } else if (menuId == R.id.menu_audios) {
                    fragment = new com.vktea.ui.AudioFragment();
                } else if (menuId == R.id.menu_friends) {
                    fragment = new com.vktea.friends.FriendsFragment();
                } else if (menuId == R.id.menu_groups) {
                    fragment = new com.vktea.groups.GroupsFragment();
                } else {
                    fragment = PlaceholderFragment.newInstance(getString(entry.titleRes), entry.iconRes);
                }
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.content, fragment)
                        .commit();
                return;
            }
        }
    }

    public void openFragment(androidx.fragment.app.Fragment fragment, int titleRes) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack(null)
                .commit();
        toolbarTitle.setText(titleRes);
        drawer.closeDrawer(menuView);
    }

    public void onMenuHeaderClick() {
        String uid = Session.getUserId(this);
        if (uid != null) {
            openFragment(com.vktea.feed.ProfileFragment.newInstance(Long.parseLong(uid)),
                    R.string.profile_title);
        }
    }
}
