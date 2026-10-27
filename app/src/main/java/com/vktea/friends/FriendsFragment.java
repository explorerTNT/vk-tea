package com.vktea.friends;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.feed.ProfileFragment;
import com.vktea.msg.ChatActivity;
import com.vktea.ui.UserSettings;

import java.util.List;

/** A read-only friend directory. Opening a row never changes friendships or sends messages. */
public class FriendsFragment extends Fragment {
    private FriendsViewModel model;
    private FriendsAdapter adapter;
    private TabLayout tabs;
    private EditText search;
    private TextView empty, notice;
    private View emptyState, loading, noticeRow, clear;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private PopupMenu menu;
    private TextWatcher watcher;
    private List<Friend> renderedRows;
    private boolean rendering;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        model = new ViewModelProvider(this, createModelFactory()).get(FriendsViewModel.class);
        if (saved != null) {
            model.setQuery(saved.getString("friends_query", ""));
            model.setOnlineOnly(saved.getBoolean("friends_online", false));
        }
    }

    protected ViewModelProvider.Factory createModelFactory() {
        return new FriendsViewModel.Factory(requireContext());
    }

    @Override public void onResume() {
        super.onResume();
        model.setOrder(UserSettings.friendsOrder(requireContext()));
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                                                @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_friends, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        tabs = view.findViewById(R.id.friends_tabs);
        search = view.findViewById(R.id.friends_search);
        clear = view.findViewById(R.id.friends_clear_search);
        empty = view.findViewById(R.id.friends_empty);
        emptyState = view.findViewById(R.id.friends_empty_state);
        loading = view.findViewById(R.id.friends_loading);
        notice = view.findViewById(R.id.friends_notice);
        noticeRow = view.findViewById(R.id.friends_notice_row);
        refresh = view.findViewById(R.id.friends_refresh);
        list = view.findViewById(R.id.friends_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FriendsAdapter(new FriendsAdapter.Listener() {
            @Override public void openProfile(Friend friend) { FriendsFragment.this.openProfile(friend); }
            @Override public void openChat(Friend friend) { FriendsFragment.this.openChat(friend); }
        });
        list.setAdapter(adapter);
        tabs.addTab(tabs.newTab().setText(R.string.friends_all));
        tabs.addTab(tabs.newTab().setText(R.string.friends_online));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!rendering) { model.setOnlineOnly(tab.getPosition() == 1); list.scrollToPosition(0); }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!rendering) { model.setQuery(value.toString()); list.scrollToPosition(0); }
            }
            @Override public void afterTextChanged(Editable value) { }
        };
        search.addTextChangedListener(watcher);
        clear.setOnClickListener(v -> model.setQuery(""));
        refresh.setColorSchemeColors(com.vktea.ui.TeaColors.get(requireContext(), R.color.vk_blue));
        refresh.setOnRefreshListener(model::refresh);
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.friends_retry).setOnClickListener(v -> model.retry());
        model.state().observe(getViewLifecycleOwner(), this::render);
        model.ensureLoaded();
    }

    private void render(FriendsViewModel.State state) {
        if (adapter == null) return;
        rendering = true;
        tabs.getTabAt(0).setText(state.loaded ? getString(R.string.friends_all_count, state.totalCount) : getString(R.string.friends_all));
        tabs.getTabAt(1).setText(state.loaded ? getString(R.string.friends_online_count, state.onlineCount) : getString(R.string.friends_online));
        tabs.selectTab(tabs.getTabAt(state.onlineOnly ? 1 : 0));
        if (!search.getText().toString().equals(state.query)) {
            search.setText(state.query);
            search.setSelection(search.length());
        }
        clear.setVisibility(state.query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (!state.visibleItems.equals(renderedRows)) {
            renderedRows = state.visibleItems;
            adapter.submit(state.visibleItems);
        }
        refresh.setRefreshing(state.refreshing);
        emptyState.setVisibility(state.visibleItems.isEmpty() ? View.VISIBLE : View.GONE);
        loading.setVisibility(state.loading && !state.loaded ? View.VISIBLE : View.GONE);
        empty.setText(state.loading && !state.loaded ? R.string.friends_loading
                : state.error != null && !state.loaded ? R.string.friends_load_failed
                : !state.query.trim().isEmpty() ? R.string.friends_not_found
                : state.onlineOnly ? R.string.friends_no_online : R.string.friends_empty);
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error));
        rendering = false;
    }

    private static int errorText(FriendsError error) {
        switch (error.kind) {
            case AUTH: return R.string.friends_error_auth;
            case ACCESS: return R.string.friends_error_access;
            case RATE_LIMITED: return R.string.friends_error_rate;
            case NETWORK: return R.string.friends_error_network;
            default: return R.string.friends_error_generic;
        }
    }

    public void showFriendsSearch() {
        if (search == null) return;
        EditText target = search;
        target.requestFocus();
        target.post(() -> {
            if (search == target && target.getWindowToken() != null) {
                InputMethodManager keyboard = (InputMethodManager) target.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public void showFriendsMenu(View anchor) {
        if (adapter == null) return;
        if (menu != null) menu.dismiss();
        menu = new PopupMenu(requireContext(), anchor);
        FriendsViewModel.State state = model.getCurrentState();
        menu.getMenu().add(0, 1, 0, R.string.friends_all).setCheckable(true).setChecked(!state.onlineOnly);
        menu.getMenu().add(0, 2, 1, R.string.friends_online).setCheckable(true).setChecked(state.onlineOnly);
        menu.getMenu().add(1, 3, 2, R.string.friends_refresh).setEnabled(!state.loading);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 3) model.refresh();
            else { model.setOnlineOnly(item.getItemId() == 2); list.scrollToPosition(0); }
            return true;
        });
        menu.show();
    }

    protected void openProfile(Friend friend) {
        hideSearchKeyboard();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openFragment(ProfileFragment.newInstance(friend.id), R.string.profile_title);
        }
    }

    protected void openChat(Friend friend) {
        hideSearchKeyboard();
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_PEER_ID, friend.id);
        intent.putExtra(ChatActivity.EXTRA_TITLE, friend.name);
        intent.putExtra(ChatActivity.EXTRA_PHOTO, friend.photo);
        startActivity(intent);
    }

    private void hideSearchKeyboard() {
        if (search == null) return;
        InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("friends_query", model.getCurrentState().query);
        state.putBoolean("friends_online", model.getCurrentState().onlineOnly);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroyView() {
        if (menu != null) menu.dismiss();
        menu = null;
        search.removeTextChangedListener(watcher);
        list.setAdapter(null);
        adapter = null;
        renderedRows = null;
        watcher = null;
        tabs = null;
        search = null;
        clear = emptyState = loading = noticeRow = null;
        empty = notice = null;
        list = null;
        refresh = null;
        super.onDestroyView();
    }
}
