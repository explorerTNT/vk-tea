package com.vktea.msg;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

public class DialogsFragment extends Fragment {
    private DialogsAdapter adapter;
    private SwipeRefreshLayout refresh;
    private int generation;
    private boolean startedOnce;
    private boolean loading;
    private boolean livePending;
    private final MessageLongPoll.Listener liveUpdates = this::onLiveMessagesChanged;
    private final Runnable sessionUpdates = this::onSessionChanged;
    private long sessionGeneration;
    @Nullable private String sessionUser;
    private boolean sessionRevoked;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Session.Snapshot initial = Session.snapshot(requireContext());
        sessionGeneration = initial.generation;
        sessionUser = initial.userId;
        Session.addChangeListener(sessionUpdates);
    }

    /** Discard rows only across accounts; token refresh may retain the same user's snapshot. */
    private boolean synchronizeSession() {
        Session.Snapshot current = Session.snapshot(requireContext());
        if (current.generation == sessionGeneration) return false;
        boolean changedUser = !Objects.equals(sessionUser, current.userId) || current.token == null || current.token.isEmpty();
        sessionGeneration = current.generation;
        sessionUser = current.userId;
        sessionRevoked = current.token == null || current.token.isEmpty();
        ++generation;
        loading = false;
        livePending = false;
        if (refresh != null) refresh.setRefreshing(false);
        if (changedUser) {
            if (adapter != null) adapter.clear();
            DialogsParser.setSelfId(0);
        }
        return true;
    }

    private void onSessionChanged() {
        if (!isAdded() || !synchronizeSession()) return;
        if (adapter != null && getView() != null && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)
                && !sessionRevoked) load();
    }

    protected void onLiveMessagesChanged() {
        if (loading) livePending = true;
        else load();
    }

    @Override public void onStart() {
        super.onStart();
        boolean changedSession = synchronizeSession();
        subscribeLiveUpdates();
        if (!sessionRevoked && (startedOnce || changedSession)) load();
        startedOnce = true;
    }

    @Override public void onStop() {
        unsubscribeLiveUpdates();
        livePending = false;
        super.onStop();
    }

    protected void subscribeLiveUpdates() { MessageLongPoll.get(requireContext()).addListener(liveUpdates); }
    protected void unsubscribeLiveUpdates() { MessageLongPoll.get(requireContext()).removeListener(liveUpdates); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_dialogs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        refresh = view.findViewById(R.id.dialogs_refresh);
        RecyclerView list = view.findViewById(R.id.dialogs_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DialogsAdapter();
        list.setAdapter(adapter);
        refresh.setOnRefreshListener(this::load);
        load();
    }

    protected void load() {
        if (adapter == null || refresh == null) return;
        if (Session.generation() != sessionGeneration) { onSessionChanged(); return; }
        if (sessionRevoked) return;
        final int request = ++generation;
        loading = true;
        final DialogsAdapter target = adapter;
        refresh.setRefreshing(true);
        requestDialogs(new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (!current(request, target)) return;
                JSONObject data = response == null ? null : response.optJSONObject("response");
                if (data != null && data.optJSONArray("items") != null) {
                    String selfId = sessionUser;
        if (selfId != null && !selfId.isEmpty()) {
            DialogsParser.setSelfId(Long.parseLong(selfId));
        }
        List<Dialog> dialogs = DialogsParser.parse(response);
                    target.clear();
                    for (Dialog dialog : dialogs) target.add(dialog);
                    applyCachedPeers(target);
                    loadAvatars(request, target);
                }
                finishLoad();
            }
            @Override public void error(Exception e) {
                if (current(request, target)) finishLoad();
            }
        });
    }

    private void finishLoad() {
        loading = false;
        refresh.setRefreshing(false);
        if (livePending) {
            livePending = false;
            load();
        }
    }

    /** Transport boundaries also allow isolated UI checks without opening account conversations. */
    protected void requestDialogs(VKApi.Callback callback) {
        VKApi.get(requireContext(), "messages.getDialogs", "count=40&preview_length=64", callback);
    }

    protected void requestUsers(String ids, VKApi.Callback callback) {
        VKApi.get(requireContext(), "users.get", "user_ids=" + ids
                + "&fields=photo_200,photo_100,photo_50", callback);
    }

    protected void requestGroups(String ids, VKApi.Callback callback) {
        VKApi.get(requireContext(), "groups.getById", "group_ids=" + ids, callback);
    }

    private void applyCachedPeers(DialogsAdapter target) {
        long self = DialogsParser.selfId();
        for (Dialog dialog : target.items()) {
            if (dialog.peerId == self) continue; // «Избранное» не трогаем
            String[] cached = PeerCache.load(requireContext(), dialog.peerId);
            if (cached != null) target.applyUser(dialog.peerId, cached[0], cached[1]);
        }
    }

    private void loadAvatars(int request, DialogsAdapter target) {
        Set<String> users = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        long self = DialogsParser.selfId();
        for (Dialog dialog : target.items()) {
            if (dialog.peerId == self) continue; // «Избранное» — имя не перезаписываем
            if (dialog.peerId > 0 && dialog.peerId < 2_000_000_000L) {
                users.add(String.valueOf(dialog.peerId));
            } else if (dialog.peerId < 0 && dialog.peerId != Long.MIN_VALUE) {
                groups.add(String.valueOf(-dialog.peerId));
            }
        }
        if (!users.isEmpty()) requestUsers(android.text.TextUtils.join(",", users), peersCallback(request, target, false));
        if (!groups.isEmpty()) requestGroups(android.text.TextUtils.join(",", groups), peersCallback(request, target, true));
    }

    private VKApi.Callback peersCallback(int request, DialogsAdapter target, boolean groups) {
        return new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (!current(request, target) || response == null) return;
                JSONArray peers = response.optJSONArray("response");
                if (peers == null) {
                    JSONObject data = response.optJSONObject("response");
                    if (data != null) peers = data.optJSONArray(groups ? "groups" : "users");
                }
                if (peers == null) return;
                for (int index = 0; index < peers.length(); index++) {
                    JSONObject peer = peers.optJSONObject(index);
                    if (peer == null) continue;
                    long id = peer.optLong("id", 0);
                    if (id <= 0) continue;
                    String name = groups ? peer.optString("name", "")
                            : (peer.optString("first_name", "") + " " + peer.optString("last_name", "")).trim();
                    String photo = MessageAvatars.photoUrl(peer);
                    PeerCache.store(requireContext(), groups ? -id : id, name, photo);
                    target.applyUser(groups ? -id : id, name, photo);
                }
            }
            @Override public void error(Exception e) { }
        };
    }

    private boolean current(int request, DialogsAdapter target) {
        if (!isAdded()) return false;
        if (Session.generation() != sessionGeneration) { onSessionChanged(); return false; }
        return request == generation && target == adapter && getView() != null;
    }

    @Override public void onDestroyView() {
        ++generation;
        loading = false;
        livePending = false;
        adapter = null;
        refresh = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        Session.removeChangeListener(sessionUpdates);
        super.onDestroy();
    }
}
