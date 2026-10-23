package com.vktea.auth;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

import okhttp3.HttpUrl;

public class AuthActivity extends AppCompatActivity {

    private static final String TOKEN_URL = "https://oauth.vk.com/token";
    private static final String CLIENT_ID = "2274003";
    private static final String CLIENT_SECRET = "hHbZxrka2uZ6jB1inYsH";
    private static final String SCOPE = "nohttps,all";
    private static final String API_V = "5.82";

    private static final int VALIDATION_REQUEST = 1;
    private EditText loginField;
    private EditText passField;
    private TextView loginButton;
    private View tokenButton;
    private TextView status;
    private AuthAttemptViewModel requests;
    private AuthHttpClient authClient;
    private String captchaSid;
    private String captchaKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        loginField = findViewById(R.id.auth_login);
        passField = findViewById(R.id.auth_pass);
        loginButton = findViewById(R.id.auth_login_btn);
        tokenButton = findViewById(R.id.auth_token_btn);
        status = findViewById(R.id.auth_status);
        authClient = createAuthClient();
        requests = new ViewModelProvider(this).get(AuthAttemptViewModel.class);
        loginButton.setOnClickListener(v -> doAuth());
        tokenButton.setOnClickListener(v -> showTokenLogin());
        findViewById(R.id.auth_forgot).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.vk.com/restore"))));
        requests.getBusy().observe(this, busy -> setBusy(Boolean.TRUE.equals(busy)));
        requests.getResponse().observe(this, response -> {
            if (response != null && !isFinishing()) {
                AuthResponse result = requests.takeResponse();
                if (result != null) {
                    handleResponse(result);
                }
            }
        });
    }

    /** Kept injectable for offline tests; production transport never retries auth. */
    protected AuthHttpClient createAuthClient() {
        return new AuthHttpClient();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VALIDATION_REQUEST) {
            // Back/cancel is not proof that validation succeeded. Any continuation
            // requires an explicit user action, never a lifecycle-triggered retry.
            showStatus(R.string.auth_validation_returned);
        }
    }

    private void doAuth() {
        if (requests.isBusy()) {
            showStatus(R.string.auth_request_in_progress);
            return;
        }
        String login = loginField.getText().toString().trim();
        String password = passField.getText().toString();
        if (login.isEmpty() || password.isEmpty()) {
            showStatus(R.string.auth_rejected);
            return;
        }
        HttpUrl.Builder url = HttpUrl.get(TOKEN_URL).newBuilder()
                .addQueryParameter("scope", SCOPE)
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("client_secret", CLIENT_SECRET)
                .addQueryParameter("v", API_V)
                .addQueryParameter("2fa_supported", "1")
                .addQueryParameter("lang", java.util.Locale.getDefault().getLanguage())
                .addQueryParameter("libverify_support", "1")
                .addQueryParameter("grant_type", "password")
                .addQueryParameter("username", login)
                .addQueryParameter("password", password);
        String trustedHash = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("trusted_hash", null);
        if (trustedHash != null && !trustedHash.isEmpty()) {
            url.addQueryParameter("trusted_hash", trustedHash);
        }
        if (captchaSid != null && !captchaKey.isEmpty()) {
            url.addQueryParameter("captcha_sid", captchaSid)
                    .addQueryParameter("captcha_key", captchaKey);
        }
        HttpUrl requestUrl = url.build();
        AuthHttpClient client = authClient;
        if (requests.submit(AuthResponse.Method.PASSWORD, () -> client.password(requestUrl))) {
            captchaSid = null;
            captchaKey = "";
        } else {
            showStatus(R.string.auth_request_in_progress);
        }
    }

    private void handleResponse(AuthResponse response) {
        if (response.networkFailure) {
            showStatus(R.string.auth_network_failed);
            return;
        }
        if (AuthErrors.isRateLimited(response)) {
            showStatus(R.string.auth_rate_limited);
            return;
        }
        JSONObject json = response.json != null ? response.json : new JSONObject();
        if (response.method == AuthResponse.Method.TOKEN) {
            org.json.JSONArray users = json.optJSONArray("response");
            JSONObject user = users != null ? users.optJSONObject(0) : null;
            if (response.httpStatus >= 200 && response.httpStatus < 300 && user != null
                    && user.optLong("id") > 0) {
                Session.save(this, response.suppliedToken, user.optString("id"), response.suppliedSecret);
                TokenRevealDialog.show(this, response.suppliedToken);
            } else {
                showStatus(R.string.token_invalid);
            }
            return;
        }
        String token = json.optString("access_token", "");
        String userId = json.optString("user_id", "");
        if (response.httpStatus >= 200 && response.httpStatus < 300
                && !token.isEmpty() && json.optLong("user_id", 0) > 0) {
            String trustedHash = json.optString("trusted_hash", "");
            if (!trustedHash.isEmpty()) {
                getSharedPreferences("auth", MODE_PRIVATE).edit()
                        .putString("trusted_hash", trustedHash).apply();
            }
            Session.save(this, token, userId, json.optString("secret", null));
            passField.setText("");
            TokenRevealDialog.show(this, token);
        } else if (json.has("captcha_sid") && json.has("captcha_img")) {
            captchaSid = json.optString("captcha_sid");
            showCaptcha(json.optString("captcha_img"));
        } else if ("need_validation".equals(json.optString("error")) && json.has("validation_sid")) {
            String redirect = json.optString("redirect_uri",
                    "https://m.vk.com/login?act=authcheck&sid=" + json.optString("validation_sid"));
            Intent intent = new Intent(this, AuthWebViewActivity.class);
            intent.setData(Uri.parse(redirect));
            startActivityForResult(intent, VALIDATION_REQUEST);
        } else {
            showStatus(R.string.auth_rejected);
        }
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        tokenButton.setEnabled(!busy);
        loginField.setEnabled(!busy);
        passField.setEnabled(!busy);
        loginButton.setText(busy ? R.string.auth_in_progress : R.string.log_in);
        if (busy) {
            showStatus(R.string.auth_request_in_progress);
        }
    }

    private void showStatus(int message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void showTokenLogin() {
        if (requests.isBusy()) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        EditText input = new EditText(this);
        input.setHint(R.string.token_hint);
        input.setSingleLine(false);
        input.setMinLines(2);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.token_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> checkToken(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void checkToken(String input) {
        if (input.isEmpty()) {
            return;
        }
        String[] parts = input.trim().split("\\s+");
        String token = parts[0];
        String secret = parts.length > 1 ? parts[1] : null;
        AuthHttpClient client = authClient;
        if (!requests.submit(AuthResponse.Method.TOKEN, () -> client.token(token, secret))) {
            showStatus(R.string.auth_request_in_progress);
        }
    }

    private void showCaptcha(String imageUrl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(this);
        input.setHint(R.string.captcha_title);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.captcha_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    captchaKey = input.getText().toString();
                    if (!captchaKey.isEmpty()) {
                        doAuth();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> captchaSid = null)
                .setOnCancelListener(d -> captchaSid = null)
                .show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                Bitmap bitmap;
                try (java.io.InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && dialog.isShowing()) {
                        image.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showStatus(R.string.auth_captcha_failed);
                    }
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "auth-captcha-image").start();
    }
}
