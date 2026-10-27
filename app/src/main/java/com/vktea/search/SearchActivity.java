package com.vktea.search;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.vktea.R;
import com.vktea.auth.Session;
import com.vktea.feed.PostActivity;
import com.vktea.feed.VideoPlayerActivity;
import java.util.ArrayList;
import java.util.List;

/** Global search. Each tab is requested independently, preserving partial availability. */
public class SearchActivity extends AppCompatActivity {
    public static final String EXTRA_PROFILE_ID = "com.vktea.search.PROFILE_ID";
    private final List<SearchParser.Item> items = new ArrayList<>();
    private SearchRepository repository;
    private SearchParser.Kind kind = SearchParser.Kind.USERS;
    private String query = "", cursor = "";
    private boolean loading, loaded;
    private EditText input;
    private TextView status;
    private Button more, retry;
    private ProgressBar progress;
    private ResultsAdapter adapter;
    private RecyclerView list;
    private String accountToken, accountSecret, accountUser;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        repository = new SearchRepository(this);
        accountToken = Session.getToken(this); accountSecret = Session.getSecret(this); accountUser = Session.getUserId(this);
        LinearLayout root = new LinearLayout(this);
        root.setFitsSystemWindows(true);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.menu_selected_bg));
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setBackgroundColor(getColor(R.color.vk_blue));
        toolbar.setTitleTextColor(0xffffffff);
        toolbar.setTitle("Поиск");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationIconTint(android.graphics.Color.WHITE);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setPadding(dp(12), dp(4), dp(8), dp(4));
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(getColor(R.color.menu_bg));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Имя, название или текст");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button search = new Button(this);
        search.setText("Найти");
        search.setOnClickListener(v -> submit());
        searchBar.addView(search);
        root.addView(searchBar);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_UP)) { submit(); return true; }
            return false;
        });
        TabLayout tabs = new TabLayout(this);
        tabs.setBackgroundColor(getColor(R.color.menu_bg));
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.addTab(tabs.newTab().setText("Люди"));
        tabs.addTab(tabs.newTab().setText("Сообщества"));
        tabs.addTab(tabs.newTab().setText("Публикации"));
        tabs.addTab(tabs.newTab().setText("Видео"));
        root.addView(tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { kind = SearchParser.Kind.values()[tab.getPosition()]; resetAndLoad(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });

        progress = new ProgressBar(this);
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setGravity(Gravity.CENTER);
        progressRow.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(progressRow);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(status);
        retry = new Button(this);
        retry.setText("Повторить");
        retry.setOnClickListener(v -> load(!items.isEmpty()));
        root.addView(retry);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultsAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        more = new Button(this);
        more.setText("Показать ещё");
        more.setOnClickListener(v -> load(true));
        root.addView(more);
        setContentView(root);
        if (saved != null) {
            input.setText(saved.getString("input", ""));
            query = saved.getString("query", "");
            int tab = saved.getInt("tab", 0);
            if (tab >= 0 && tab < SearchParser.Kind.values().length && tab != 0) tabs.selectTab(tabs.getTabAt(tab));
            else resetAndLoad();
        } else render(null);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void submit() {
        query = input.getText().toString().trim();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        resetAndLoad();
    }
    private void resetAndLoad() {
        repository.cancel();
        items.clear(); cursor = ""; loaded = false; loading = false;
        adapter.notifyDataSetChanged();
        list.scrollToPosition(0);
        if (query.isEmpty()) render(null); else load(false);
    }
    private void load(boolean nextPage) {
        if (loading || query.isEmpty()) return;
        loading = true;
        render(null);
        repository.search(kind, query, nextPage ? cursor : "", (page, error) -> {
            if (isFinishing() || isDestroyed()) return;
            loading = false;
            if (!java.util.Objects.equals(accountToken, Session.getToken(this))
                    || !java.util.Objects.equals(accountSecret, Session.getSecret(this))
                    || !java.util.Objects.equals(accountUser, Session.getUserId(this))) {
                items.clear(); cursor = ""; loaded = false; adapter.notifyDataSetChanged();
                accountToken = Session.getToken(this); accountSecret = Session.getSecret(this); accountUser = Session.getUserId(this);
                render("Аккаунт изменился. Повторите поиск."); return;
            }
            if (error != null || page == null) { render(error == null ? "Не удалось загрузить результаты." : error); return; }
            if (!nextPage) items.clear();
            items.addAll(page.items);
            cursor = nextPage && cursor.equals(page.next) ? "" : page.next;
            loaded = true;
            adapter.notifyDataSetChanged();
            render(null);
        });
    }
    private void render(String error) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        retry.setVisibility(error != null ? View.VISIBLE : View.GONE);
        more.setVisibility(!loading && error == null && !cursor.isEmpty() ? View.VISIBLE : View.GONE);
        String message = error != null ? error : query.isEmpty() ? "Введите запрос и нажмите «Найти»." : loading && items.isEmpty() ? "Ищем…" : loaded && items.isEmpty() ? "Ничего не найдено." : "";
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putString("input", input.getText().toString()); out.putString("query", query); out.putInt("tab", kind.ordinal());
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!java.util.Objects.equals(accountToken, Session.getToken(this))
                || !java.util.Objects.equals(accountSecret, Session.getSecret(this))
                || !java.util.Objects.equals(accountUser, Session.getUserId(this))) {
            accountToken = Session.getToken(this); accountSecret = Session.getSecret(this); accountUser = Session.getUserId(this);
            resetAndLoad();
        }
    }
    @Override protected void onDestroy() { repository.cancel(); super.onDestroy(); }
    static Intent videoIntent(android.content.Context context, SearchParser.Item item) {
        if (item == null || item.id == 0 || item.videoId <= 0) return null;
        Intent intent = new Intent(context, VideoPlayerActivity.class)
                .putExtra("owner_id", item.id).putExtra("video_id", item.videoId);
        if (!item.videoAccessKey.isEmpty()) intent.putExtra("access_key", item.videoAccessKey);
        return intent;
    }
    private final class ResultsAdapter extends RecyclerView.Adapter<ResultHolder> {
        @NonNull @Override public ResultHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(SearchActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(10), dp(16), dp(10)); row.setBackgroundColor(getColor(R.color.menu_bg));
            ImageView photo = new ImageView(SearchActivity.this);
            row.addView(photo, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout text = new LinearLayout(SearchActivity.this); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(12), 0, 0, 0);
            TextView title = new TextView(SearchActivity.this); title.setTextColor(getColor(R.color.almost_black)); title.setTextSize(16); title.setMaxLines(2);
            TextView subtitle = new TextView(SearchActivity.this); subtitle.setTextColor(getColor(R.color.post_time)); subtitle.setTextSize(14); subtitle.setMaxLines(4);
            text.addView(title); text.addView(subtitle); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            return new ResultHolder(row, photo, title, subtitle);
        }
        @Override public void onBindViewHolder(@NonNull ResultHolder holder, int position) {
            SearchParser.Item item = items.get(position);
            holder.title.setText(item.title); holder.subtitle.setText(item.subtitle);
            Glide.with(holder.photo).load(item.photo).apply(item.videoId != 0 ? RequestOptions.centerCropTransform() : RequestOptions.circleCropTransform()).placeholder(android.R.drawable.sym_def_app_icon).into(holder.photo);
            holder.itemView.setOnClickListener(v -> {
                if (item.post != null) PostActivity.start(SearchActivity.this, item.post);
                else if (item.videoId > 0 && item.id != 0) {
                    Intent intent = videoIntent(SearchActivity.this, item);
                    if (intent != null) startActivity(intent);
                }
                else { setResult(RESULT_OK, new Intent().putExtra(EXTRA_PROFILE_ID, item.id)); finish(); }
            });
        }
        @Override public int getItemCount() { return items.size(); }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final ImageView photo; final TextView title, subtitle;
        ResultHolder(View row, ImageView photo, TextView title, TextView subtitle) { super(row); this.photo = photo; this.title = title; this.subtitle = subtitle; }
    }
}
