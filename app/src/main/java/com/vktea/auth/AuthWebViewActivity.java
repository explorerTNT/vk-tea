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

import com.vktea.BuildConfig;
import com.vktea.MainActivity;
import com.vktea.R;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class AuthWebViewActivity extends AppCompatActivity {

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                android.util.Log.d("VKTea", "auth page: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("(function(){return document.body ? document.body.innerText.substring(0,400) : ''})()", value -> android.util.Log.d("VKTea", "auth page text: " + value));
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
        Uri data = intent.getData();
        if (data != null && data.toString().startsWith(redirectUri())) {
            codeVerifier = loadCodeVerifier();
            deviceId = loadDeviceId();
            onVkIdRedirect(data.toString());
        }
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
        android.util.Log.d("VKTea", "redirect: " + url);
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
            android.util.Log.e("VKTea", "redirect rejected: code=" + code + " state=" + state);
            Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void exchangeCode(String code) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                String form = "grant_type=authorization_code"
                        + "&code=" + code
                        + "&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri(), "UTF-8")
                        + "&code_verifier=" + codeVerifier
                        + "&device_id=" + deviceId
                        + "&state=" + STATE;
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(form.getBytes(StandardCharsets.UTF_8));
                }
                Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                String body = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                JSONObject json = new JSONObject(body);
                String token = json.optString("access_token", null);
                String userId = String.valueOf(json.optLong("user_id", 0));
                if (token != null && !"0".equals(userId)) {
                    runOnUiThread(() -> {
                        Session.save(this, token, userId);
                        TokenRevealDialog.show(this, token);
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
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
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
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
