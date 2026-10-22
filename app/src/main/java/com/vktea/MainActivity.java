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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isAuthorized(this)) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        menu = buildMenu();

        drawer = findViewById(R.id.drawer);
        View burger = findViewById(R.id.toolbar_burger);
        toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarBadge = findViewById(R.id.toolbar_badge);

        burger.setOnClickListener(v -> drawer.openDrawer(menuView));

        View titleBtn = findViewById(R.id.toolbar_title_btn);
        titleBtn.setOnClickListener(this::showSectionDropdown);

        findViewById(R.id.toolbar_search).setOnClickListener(v ->
                Toast.makeText(this, R.string.section_search, Toast.LENGTH_SHORT).show());

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

        com.vktea.api.VKApi.get(this, "users.get", "fields=photo_200,status,counters", new com.vktea.api.VKApi.Callback() {
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
                    final com.vktea.api.User user = new com.vktea.api.User(
                            u.getLong("id"),
                            u.optString("first_name", ""),
                            u.optString("last_name", ""),
                            u.optString("photo_200", null),
                            u.optString("status", ""),
                            counters);
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
                    toolbarTitle.setText(entry.titleRes);
                    break;
                }
            }
        }
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
                toolbarTitle.setText(entry.titleRes);
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
    }
}
