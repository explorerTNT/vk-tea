package com.vktea.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Быстрый поиск по мотивам Coffee QuickSearchActivity:
 * подсказки search.getHints, люди users.search, сообщества groups.search.
 */
public class SearchActivity extends AppCompatActivity {

    static final class Result {
        final long id;
        final String title;
        final String subtitle;
        final String avatar;
        final boolean group;
        Result(long id, String title, String subtitle, String avatar, boolean group) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.avatar = avatar;
            this.group = group;
        }
    }

    private EditText input;
    private RecyclerView list;
    private TextView state;
    private View loading;
    private final List<Result> results = new ArrayList<>();
    private int tab;
    private boolean queryActive;
    private String pendingQuery;
    private int requestGeneration;
    private final Handler debounce = new Handler();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        input = findViewById(R.id.search_input);
        list = findViewById(R.id.search_list);
        state = findViewById(R.id.search_state);
        loading = findViewById(R.id.search_loading);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new Adapter());
        findViewById(R.id.search_back).setOnClickListener(v -> finish());
        com.google.android.material.tabs.TabLayout tabs = findViewById(R.id.search_tabs);
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab t) {
                tab = t.getPosition();
                search(input.getText().toString().trim());
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab t) { }
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab t) { }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                search(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                debounce.removeCallbacksAndMessages(null);
                pendingQuery = s.toString().trim();
                if (pendingQuery.isEmpty()) {
                    showEmpty(R.string.search_empty_query);
                    return;
                }
                debounce.postDelayed(() -> search(pendingQuery), 400);
            }
        });
        showEmpty(R.string.search_empty_query);
        input.requestFocus();
    }

    private void showEmpty(int res) {
        results.clear();
        list.getAdapter().notifyDataSetChanged();
        state.setText(res);
        state.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
    }

    private void search(String query) {
        if (query.isEmpty()) {
            showEmpty(R.string.search_empty_query);
            return;
        }
        if (!Session.isAuthorized(this)) {
            showEmpty(R.string.search_failed);
            return;
        }
        if (queryActive) return;
        queryActive = true;
        final int generation = ++requestGeneration;
        final String encoded = android.net.Uri.encode(query);
        String method;
        String params;
        if (tab == 0) {
            method = "search.getHints";
            params = "q=" + encoded
                    + "&fields=photo_100,screen_name&filters=users,groups&count=30";
        } else if (tab == 1) {
            method = "users.search";
            params = "q=" + encoded
                    + "&count=30&fields=photo_100,online,last_seen";
        } else {
            method = "groups.search";
            params = "q=" + encoded
                    + "&count=30&fields=photo_100,members_count";
        }
        final boolean hints = tab == 0;
        loading.setVisibility(View.VISIBLE);
        state.setVisibility(View.GONE);
        VKApi.get(this, method, params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (generation != requestGeneration) return;
                JSONObject data = response == null ? null : response.optJSONObject("response");
                JSONArray items;
                if (hints) {
                    JSONObject hintsData = data == null ? null : data.optJSONObject("hints");
                    items = hintsData == null ? null : hintsData.optJSONArray("items");
                } else {
                    items = data == null ? null : data.optJSONArray("items");
                }
                List<Result> parsed = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject row = items.optJSONObject(i);
                    if (row == null) continue;
                    if (hints) {
                        String type = row.optString("type", "");
                        JSONObject value = row.optJSONObject(type);
                        if (value == null) continue;
                        if ("profile".equals(type) || "user".equals(type)) {
                            parsed.add(new Result(value.optLong("id", 0),
                                    value.optString("first_name", "") + " " + value.optString("last_name", ""),
                                    getString(R.string.search_type_user),
                                    value.optString("photo_100", ""), false));
                        } else if ("group".equals(type)) {
                            parsed.add(new Result(-Math.abs(value.optLong("id", 0)),
                                    value.optString("name", "Сообщество"),
                                    getString(R.string.search_type_group),
                                    value.optString("photo_100", ""), true));
                        }
                    } else if (tab == 1) {
                        parsed.add(new Result(row.optLong("id", 0),
                                row.optString("first_name", "") + " " + row.optString("last_name", ""),
                                getString(R.string.search_type_user),
                                row.optString("photo_100", ""), false));
                    } else {
                        parsed.add(new Result(-Math.abs(row.optLong("id", 0)),
                                row.optString("name", "Сообщество"),
                                getString(R.string.search_group_members,
                                        String.valueOf(row.optLong("members_count", 0))),
                                row.optString("photo_100", ""), true));
                    }
                }
                runOnUiThread(() -> {
                    queryActive = false;
                    loading.setVisibility(View.GONE);
                    results.clear();
                    results.addAll(parsed);
                    list.getAdapter().notifyDataSetChanged();
                    if (results.isEmpty()) showEmpty(R.string.search_nothing);
                });
            }

            @Override public void error(Exception e) {
                if (generation != requestGeneration) return;
                runOnUiThread(() -> {
                    queryActive = false;
                    showEmpty(R.string.search_failed);
                });
            }
        });
    }

    private void open(Result result) {
        if (result.id == 0) return;
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PROFILE, result.id));
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Result result = results.get(position);
            holder.title.setText(result.title);
            holder.subtitle.setText(result.subtitle);
            Glide.with(holder.avatar).load(result.avatar)
                    .circleCrop()
                    .placeholder(R.drawable.chat_avatar_placeholder)
                    .into(holder.avatar);
            holder.itemView.setOnClickListener(v -> open(result));
        }

        @Override public int getItemCount() { return results.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView title, subtitle;
            Holder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.search_avatar);
                title = itemView.findViewById(R.id.search_title);
                subtitle = itemView.findViewById(R.id.search_subtitle);
            }
        }
    }
}
