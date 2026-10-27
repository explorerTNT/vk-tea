package com.vktea.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit account changes always recreate the application task. */
public class AccountActivity extends com.vktea.ui.TeaActivity {
    private LinearLayout rows;
    @Override protected void onCreate(Bundle state) {
        boolean pendingReveal = com.vktea.auth.Session.hasPendingReveal(this);
        super.onCreate(pendingReveal ? null : state);
        if (com.vktea.auth.TokenRevealActivity.redirectIfPending(this)) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        TextView back = row(getString(R.string.accounts_back), null);
        back.setOnClickListener(v -> finish());
        root.addView(back);
        TextView title = row(getString(R.string.accounts_title), null);
        title.setTextSize(22);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView add = row(getString(R.string.accounts_add), null);
        add.setOnClickListener(v -> {
            // A trusted hash belongs to the previous login, never to an unknown new one.
            Session.clearTrustedHash(this);
            startActivity(new Intent(this, AuthActivity.class));
        });
        root.addView(add);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (com.vktea.auth.TokenRevealActivity.redirectIfPending(this)) return;
        render();
        String userId = Session.getUserId(this);
        if (userId == null) return;
        long generation = Session.generation();
        VKApi.get(this, "users.get", "", new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (isFinishing() || isDestroyed() || generation != Session.generation()
                        || !userId.equals(Session.getUserId(AccountActivity.this))) return;
                JSONArray users = response.optJSONArray("response");
                JSONObject user = users == null ? null : users.optJSONObject(0);
                if (user == null || !userId.equals(user.optString("id"))) return;
                Session.setAccountName(AccountActivity.this, userId,
                        user.optString("first_name", "") + " " + user.optString("last_name", ""));
                render();
            }
            @Override public void error(Exception ignored) { }
        });
    }

    private void render() {
        rows.removeAllViews();
        java.util.List<String> accounts = Session.savedAccounts(this);
        if (accounts.isEmpty()) rows.addView(row(getString(R.string.accounts_none), null));
        for (String id : accounts) {
            boolean current = id.equals(Session.getUserId(this));
            String name = Session.accountName(this, id);
            String summary = getString(current ? R.string.accounts_current : R.string.accounts_saved, id);
            TextView view = row(name.isEmpty() ? summary : name + "\n" + summary, null);
            view.setOnClickListener(v -> options(id, current));
            rows.addView(view);
        }
    }

    private void options(String id, boolean current) {
        String[] options = current ? new String[]{getString(R.string.accounts_remove)}
                : new String[]{getString(R.string.accounts_select), getString(R.string.accounts_remove)};
        new AlertDialog.Builder(this).setTitle(Session.accountName(this, id).isEmpty()
                ? getString(R.string.accounts_saved, id) : Session.accountName(this, id))
                .setItems(options, (dialog, which) -> {
                    if (!current && which == 0) {
                        if (Session.switchTo(this, id)) restart(MainActivity.class);
                    } else {
                        new AlertDialog.Builder(this).setMessage(R.string.accounts_remove_confirm)
                                .setPositiveButton(android.R.string.ok, (d, w) -> {
                                    Session.removeAccount(this, id);
                                    if (current) restart(Session.hasSavedAccounts(this)
                                            ? AccountActivity.class : AuthActivity.class);
                                    else render();
                                }).setNegativeButton(android.R.string.cancel, null).show();
                    }
                }).show();
    }

    private void restart(Class<?> target) {
        Intent intent = new Intent(this, target);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
    private TextView row(String text, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        int p = (int) (16 * getResources().getDisplayMetrics().density + .5f);
        view.setPadding(p, p, p, p);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true)
                && ripple.resourceId != 0) view.setBackgroundResource(ripple.resourceId);
        if (listener != null) view.setOnClickListener(listener);
        return view;
    }
}
