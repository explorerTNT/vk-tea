package com.vktea.groups;

import android.content.Context;
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

import java.util.List;

/** Read-only community directory; filtering never joins, leaves or edits a community. */
public class GroupsFragment extends Fragment {
    private GroupsViewModel model;
    private GroupsAdapter adapter;
    private TabLayout tabs;
    private EditText search;
    private TextView empty, notice;
    private View clear, emptyState, loading, noticeRow;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private PopupMenu menu;
    private TextWatcher watcher;
    private List<Group> renderedRows;
    private boolean rendering;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        model = new ViewModelProvider(this, createModelFactory()).get(GroupsViewModel.class);
        if (saved != null) {
            model.setQuery(saved.getString("groups_query", ""));
            model.setManagementOnly(saved.getBoolean("groups_management", false));
        }
    }

    protected ViewModelProvider.Factory createModelFactory() {
        return new GroupsViewModel.Factory(requireContext());
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                                                @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_groups, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        tabs = view.findViewById(R.id.groups_tabs);
        search = view.findViewById(R.id.groups_search);
        clear = view.findViewById(R.id.groups_clear_search);
        empty = view.findViewById(R.id.groups_empty);
        emptyState = view.findViewById(R.id.groups_empty_state);
        loading = view.findViewById(R.id.groups_loading);
        notice = view.findViewById(R.id.groups_notice);
        noticeRow = view.findViewById(R.id.groups_notice_row);
        refresh = view.findViewById(R.id.groups_refresh);
        list = view.findViewById(R.id.groups_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new GroupsAdapter(this::openGroup);
        list.setAdapter(adapter);
        tabs.addTab(tabs.newTab().setText(R.string.groups_all));
        tabs.addTab(tabs.newTab().setText(R.string.groups_management));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!rendering) {
                    model.setManagementOnly(tab.getPosition() == 1);
                    list.scrollToPosition(0);
                }
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
        clear.setOnClickListener(v -> { model.setQuery(""); list.scrollToPosition(0); });
        refresh.setColorSchemeResources(R.color.vk_blue);
        refresh.setOnRefreshListener(model::refresh);
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.groups_retry).setOnClickListener(v -> model.retry());
        model.state().observe(getViewLifecycleOwner(), this::render);
        model.ensureLoaded();
    }

    private void render(GroupsViewModel.State state) {
        if (adapter == null) return;
        rendering = true;
        tabs.getTabAt(0).setText(state.loaded ? getString(R.string.groups_all_count, state.totalCount) : getString(R.string.groups_all));
        tabs.getTabAt(1).setText(state.loaded ? getString(R.string.groups_management_count, state.managementCount) : getString(R.string.groups_management));
        tabs.selectTab(tabs.getTabAt(state.managementOnly ? 1 : 0));
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
        empty.setText(state.loading && !state.loaded ? R.string.groups_loading
                : state.error != null && !state.loaded ? R.string.groups_load_failed
                : !state.query.trim().isEmpty() ? R.string.groups_not_found
                : state.managementOnly ? R.string.groups_no_management : R.string.groups_empty);
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error));
        rendering = false;
    }

    private static int errorText(GroupsError error) {
        switch (error.kind) {
            case AUTH: return R.string.groups_error_auth;
            case ACCESS: return R.string.groups_error_access;
            case RATE_LIMITED: return R.string.groups_error_rate;
            case NETWORK: return R.string.groups_error_network;
            default: return R.string.groups_error_generic;
        }
    }

    public void showGroupsSearch() {
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

    public void showGroupsMenu(View anchor) {
        if (adapter == null || !isAdded()) return;
        if (menu != null) menu.dismiss();
        menu = new PopupMenu(requireContext(), anchor);
        GroupsViewModel.State state = model.getCurrentState();
        menu.getMenu().add(0, 1, 0, R.string.groups_all).setCheckable(true).setChecked(!state.managementOnly);
        menu.getMenu().add(0, 2, 1, R.string.groups_management).setCheckable(true).setChecked(state.managementOnly);
        menu.getMenu().add(1, 3, 2, R.string.groups_refresh).setEnabled(!state.loading);
        menu.setOnMenuItemClickListener(item -> {
            if (list == null) return false;
            if (item.getItemId() == 3) model.refresh();
            else { model.setManagementOnly(item.getItemId() == 2); list.scrollToPosition(0); }
            return true;
        });
        menu.show();
    }

    protected void openGroup(Group group) {
        if (!isAdded() || getView() == null || group.id <= 0) return;
        hideSearchKeyboard();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openFragment(ProfileFragment.newInstance(-group.id), R.string.groups_profile_title);
        }
    }

    private void hideSearchKeyboard() {
        if (search == null) return;
        InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("groups_query", model.getCurrentState().query);
        state.putBoolean("groups_management", model.getCurrentState().managementOnly);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroyView() {
        if (menu != null) menu.dismiss();
        menu = null;
        if (search != null && watcher != null) search.removeTextChangedListener(watcher);
        if (list != null) list.setAdapter(null);
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
