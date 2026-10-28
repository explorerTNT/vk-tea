package com.vktea.auth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vktea.R;

public class AuthSettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth_settings);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.auth_settings_toolbar);
        toolbar.setTitle(R.string.auth_settings_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("auth_settings", Context.MODE_PRIVATE);

        findViewById(R.id.auth_settings_report).setOnClickListener(v ->
                com.vktea.ui.BugReporter.report(this));

        findViewById(R.id.auth_settings_log).setOnClickListener(v -> showLog());

        Switch proxySwitch = findViewById(R.id.proxy_enabled);
        EditText host = findViewById(R.id.proxy_host);
        EditText port = findViewById(R.id.proxy_port);

        proxySwitch.setChecked(prefs.getBoolean("proxy_enabled", false));
        host.setText(prefs.getString("proxy_host", ""));
        port.setText(prefs.getString("proxy_port", ""));

        findViewById(R.id.proxy_save).setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean("proxy_enabled", proxySwitch.isChecked())
                    .putString("proxy_host", host.getText().toString().trim())
                    .putString("proxy_port", port.getText().toString().trim())
                    .apply();
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void showLog() {
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        text.setText(AuthLog.dump());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("Auth log")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .setNeutralButton(android.R.string.copy, (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("authlog", AuthLog.dump()));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static class ScrollView extends android.widget.ScrollView {
        ScrollView(Context context) {
            super(context);
        }
    }
}
