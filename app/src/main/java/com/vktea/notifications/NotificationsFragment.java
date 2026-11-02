package com.vktea.notifications;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.vktea.R;

public class NotificationsFragment extends Fragment {
    private NotificationsViewModel model;
    private NotificationsAdapter adapter;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private View emptyState, loading, noticeRow, more;
    private TextView empty, notice;
    private PopupMenu menu;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        model = new ViewModelProvider(this, createModelFactory()).get(NotificationsViewModel.class);
    }
    protected ViewModelProvider.Factory createModelFactory() { return new NotificationsViewModel.Factory(requireContext()); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_notifications, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        emptyState = view.findViewById(R.id.notifications_empty_state);
        empty = view.findViewById(R.id.notifications_empty);
        loading = view.findViewById(R.id.notifications_loading);
        noticeRow = view.findViewById(R.id.notifications_notice_row);
        notice = view.findViewById(R.id.notifications_notice);
        more = view.findViewById(R.id.notifications_more);
        refresh = view.findViewById(R.id.notifications_refresh);
        list = view.findViewById(R.id.notifications_list);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        list.setLayoutManager(layout);
        adapter = new NotificationsAdapter(this::openEvent);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) model.loadMore();
            }
        });
        refresh.setColorSchemeResources(R.color.vk_blue);
        refresh.setOnRefreshListener(() -> {
            if (model.getCurrentState().loading) refresh.setRefreshing(model.getCurrentState().refreshing);
            else model.refresh();
        });
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.notifications_retry).setOnClickListener(v -> model.retry());
        more.setOnClickListener(v -> model.loadMore());
        model.state().observe(getViewLifecycleOwner(), this::render);
        model.ensureLoaded();
    }
    private void render(NotificationsViewModel.State state) {
        if (adapter == null) return;
        adapter.submit(state.items);
        refresh.setRefreshing(state.refreshing);
        emptyState.setVisibility(state.items.isEmpty() ? View.VISIBLE : View.GONE);
        loading.setVisibility(state.loading && !state.loaded ? View.VISIBLE : View.GONE);
        empty.setText(state.loading && !state.loaded ? R.string.notifications_loading
                : state.error != null && !state.loaded ? R.string.notifications_load_failed : R.string.notifications_empty);
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error));
        more.setVisibility(state.loaded && !state.nextFrom.isEmpty() ? View.VISIBLE : View.GONE);
        more.setEnabled(!state.loading && state.error == null);
        ((TextView) more).setText(state.loadingMore ? R.string.notifications_loading_more : R.string.notifications_more);
    }
    private static int errorText(NotificationsError error) {
        switch (error.kind) {
            case AUTH: return R.string.notifications_error_auth;
            case ACCESS: return R.string.notifications_error_access;
            case RATE_LIMITED: return R.string.notifications_error_rate;
            case NETWORK: return R.string.notifications_error_network;
            default: return R.string.notifications_error_generic;
        }
    }
    public void showNotificationsMenu(View anchor) {
        if (!isAdded() || getView() == null) return;
        if (menu != null) menu.dismiss();
        menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(R.string.notifications_refresh).setEnabled(!model.getCurrentState().loading);
        menu.setOnMenuItemClickListener(item -> { model.refresh(); return true; });
        menu.show();
    }
    protected void openEvent(NotificationEvent event) {
        if (!isAdded() || event.url == null || event.url.isEmpty()) return;
        Uri uri = Uri.parse(event.url);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("vk.com") || host.equalsIgnoreCase("vk.ru"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException e) { Toast.makeText(requireContext(), R.string.notifications_no_browser, Toast.LENGTH_SHORT).show(); }
    }
    @Override public void onDestroyView() {
        if (menu != null) menu.dismiss();
        menu = null;
        if (list != null) list.setAdapter(null);
        list = null; adapter = null; refresh = null; empty = notice = null;
        emptyState = loading = noticeRow = more = null;
        super.onDestroyView();
    }
}
