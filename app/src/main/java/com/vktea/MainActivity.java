package com.vktea;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

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

    private DrawerLayout drawer;
    private RecyclerView menuView;
    private LeftMenuAdapter menuAdapter;
    private List<MenuEntry> menu;
    private int currentId;

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
        drawer.setStatusBarBackgroundColor(ContextCompat.getColor(this, R.color.vk_blue));
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, 0, 0) {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                super.onDrawerSlide(drawerView, Math.min(slideOffset, 0.02f));
            }
        };
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        menuView = findViewById(R.id.left_menu);
        menuView.setLayoutManager(new LinearLayoutManager(this));

        currentId = R.id.menu_news;
        menuAdapter = new LeftMenuAdapter(menu, this, currentId);
        menuView.setAdapter(menuAdapter);
        ViewCompat.setOnApplyWindowInsetsListener(menuView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            menuAdapter.setSystemInsets(bars.top, bars.bottom);
            return insets;
        });

        if (savedInstanceState == null) {
            showSection(R.id.menu_news);
        }
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
                getSupportActionBar().setTitle(entry.titleRes);
                menuAdapter.setSelectedId(menuId);
                androidx.fragment.app.Fragment fragment;
                if (menuId == R.id.menu_settings) {
                    fragment = new com.vktea.ui.SettingsFragment();
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
}
