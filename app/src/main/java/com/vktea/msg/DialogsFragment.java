package com.vktea.msg;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

public class DialogsFragment extends Fragment {

    private DialogsAdapter adapter;
    private SwipeRefreshLayout refresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dialogs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        refresh = view.findViewById(R.id.dialogs_refresh);
        RecyclerView list = view.findViewById(R.id.dialogs_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DialogsAdapter();
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(this::load);
        refresh.setRefreshing(true);
        load();
    }

    private void load() {
        VKApi.get(requireContext(), "messages.getDialogs", "count=40&preview_length=64",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONArray items = response.getJSONObject("response").getJSONArray("items");
                            adapter.clear();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject message = items.getJSONObject(i).getJSONObject("message");
                                long peerId = message.optLong("user_id", 0) != 0
                                        ? message.optLong("user_id")
                                        : 2000000000L + message.optLong("chat_id");
                                adapter.add(new Dialog(
                                        peerId,
                                        message.optString("title", ""),
                                        message.optString("body", ""),
                                        message.optLong("date", 0),
                                        message.optInt("out", 0) == 0 && message.optInt("read_state", 1) == 0));
                            }
                            loadAvatars();
                        } catch (Exception ignored) {
                        }
                        refresh.setRefreshing(false);
                    }

                    @Override
                    public void error(Exception e) {
                        refresh.setRefreshing(false);
                    }
                });
    }

    private void loadAvatars() {
        StringBuilder ids = new StringBuilder();
        for (Dialog d : adapter.items()) {
            if (d.peerId > 0 && d.peerId < 2000000000L) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(d.peerId);
            }
        }
        if (ids.length() == 0) {
            return;
        }
        VKApi.get(requireContext(), "users.get", "user_ids=" + ids + "&fields=photo_100",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONArray arr = response.getJSONArray("response");
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject u = arr.getJSONObject(i);
                                adapter.applyUser(u.getLong("id"),
                                        u.optString("first_name") + " " + u.optString("last_name"),
                                        u.optString("photo_100", ""));
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void error(Exception e) {
                    }
                });
    }
}
