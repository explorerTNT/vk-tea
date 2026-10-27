package com.vktea.auth;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import okhttp3.FormBody;

public class AuthWebViewActivity extends com.vktea.ui.TeaActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_VK_ID = "vk_id";

    public static final String CLIENT_ID = "2274003";

    private static final String AUTHORIZE_URL = "https://id.vk.ru/authorize";
    private static final String TOKEN_URL = "https://id.vk.ru/oauth2/auth";
    private static final String SCOPE = "offline,friends,photos,audio,video,docs,wall,messages,groups,status,notifications";
    private static final String STATE = "vktea";

    private WebView webView;
    private String codeVerifier;
    private String deviceId;
    private AuthAttemptViewModel requests;
    private AuthHttpClient authClient;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean pendingReveal = com.vktea.auth.Session.hasPendingReveal(this);
        super.onCreate(pendingReveal ? null : savedInstanceState);
        if (com.vktea.auth.TokenRevealActivity.redirectIfPending(this)) return;
        requests = new ViewModelProvider(this).get(AuthAttemptViewModel.class);
        authClient = createAuthClient();
        requests.getResponse().observe(this, response -> {
            if (response == null || isFinishing()) {
                return;
            }
            AuthResponse result = requests.takeResponse();
            if (result == null) {
                return;
            }
            JSONObject json = result.json != null ? result.json : new JSONObject();
            String token = json.optString("access_token", "");
            String userId = json.optString("user_id", "0");
            if (!result.networkFailure && result.httpStatus >= 200 && result.httpStatus < 300
                    && !token.isEmpty() && json.optLong("user_id", 0) > 0) {
                Session.saveForLogin(this, token, userId, json.optString("secret", null));
                TokenRevealDialog.start(this);
            } else {
                Toast.makeText(this, AuthErrors.isRateLimited(result)
                        ? R.string.auth_rate_limited : R.string.auth_rejected, Toast.LENGTH_LONG).show();
                finish();
            }
        });
        setContentView(R.layout.activity_auth_web);

        webView = findViewById(R.id.auth_web);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String redirect = redirectUri();
                if (MODE_VK_ID.equals(getIntent().getStringExtra(EXTRA_MODE)) && url.startsWith(redirect)) {
                    onVkIdRedirect(url);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldOverrideUrlLoading(view, request.getUrl().toString());
            }

        });

        Uri data = getIntent().getData();
        if (data != null && data.toString().startsWith(redirectUri())) {
            codeVerifier = loadCodeVerifier();
            deviceId = loadDeviceId();
            onVkIdRedirect(data.toString());
        } else if (data != null) {
            if (savedInstanceState == null) {
                webView.loadUrl(data.toString());
            }
        } else if (MODE_VK_ID.equals(getIntent().getStringExtra(EXTRA_MODE))) {
            codeVerifier = makeVerifier();
            saveCodeVerifier(codeVerifier);
            deviceId = loadDeviceId();
            openInBrowser(buildAuthUrl());
            finish();
        } else {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent.getData();
        if (data != null && data.toString().startsWith(redirectUri())) {
            codeVerifier = loadCodeVerifier();
            deviceId = loadDeviceId();
            onVkIdRedirect(data.toString());
        }
    }

    protected AuthHttpClient createAuthClient() {
        return new AuthHttpClient();
    }

    private void openInBrowser(String url) {
        try {
            androidx.browser.customtabs.CustomTabsIntent tabs =
                    new androidx.browser.customtabs.CustomTabsIntent.Builder().build();
            tabs.launchUrl(this, Uri.parse(url));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private void saveCodeVerifier(String verifier) {
        getSharedPreferences("auth", MODE_PRIVATE).edit().putString("code_verifier", verifier).apply();
    }

    private String loadCodeVerifier() {
        return getSharedPreferences("auth", MODE_PRIVATE).getString("code_verifier", "");
    }

    private static String redirectUri() {
        return "vk" + CLIENT_ID + "://vk.ru/blank.html";
    }

    private String buildAuthUrl() {
        try {
            return AUTHORIZE_URL
                    + "?client_id=" + CLIENT_ID
                    + "&response_type=code"
                    + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri(), "UTF-8")
                    + "&scope=" + SCOPE
                    + "&code_challenge=" + codeChallenge(codeVerifier)
                    + "&code_challenge_method=S256"
                    + "&device_id=" + deviceId
                    + "&state=" + STATE;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void onVkIdRedirect(String url) {
        if (requests.isBusy() || requests.getResponse().getValue() != null) {
            return;
        }
        Map<String, String> params = parseQuery(url.substring(url.indexOf('?') + 1));
        String code = params.get("code");
        String state = params.get("state");
        String device = params.get("device_id");
        if (device != null && !device.isEmpty()) {
            saveDeviceId(device);
            deviceId = device;
        }
        if (code != null && STATE.equals(state)) {
            exchangeCode(code);
        } else {
            Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void exchangeCode(String code) {
        if (codeVerifier == null || codeVerifier.isEmpty()) {
            Toast.makeText(this, R.string.auth_rejected, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        FormBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", redirectUri())
                .add("code_verifier", codeVerifier)
                .add("device_id", deviceId != null ? deviceId : "")
                .add("state", STATE)
                .build();
        AuthHttpClient client = authClient;
        if (requests.submit(AuthResponse.Method.VK_ID, () -> client.vkId(TOKEN_URL, form))) {
            // A callback code is single-use. Configuration changes retain the
            // ViewModel; later duplicate intents cannot start another exchange.
            getSharedPreferences("auth", MODE_PRIVATE).edit().remove("code_verifier").apply();
            codeVerifier = null;
        } else {
            Toast.makeText(this, R.string.auth_request_in_progress, Toast.LENGTH_LONG).show();
        }
    }

    private static String makeVerifier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(Uri.decode(pair.substring(0, eq)), Uri.decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private String loadDeviceId() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void saveDeviceId(String id) {
        getSharedPreferences("auth", MODE_PRIVATE).edit().putString("device_id", id).apply();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
