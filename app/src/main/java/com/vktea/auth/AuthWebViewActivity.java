package com.vktea.auth;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import com.vktea.MainActivity;
import com.vktea.R;

import java.util.HashMap;
import java.util.Map;

public class AuthWebViewActivity extends AppCompatActivity {

    public static final String CLIENT_ID = "4705861";
    public static final String REDIRECT_URI = "https://oauth.vk.com/blank.html";
    public static final String SCOPE = "friends,photos,audio,video,docs,wall,messages,offline,groups,status,notifications";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth_web);

        webView = findViewById(R.id.auth_web);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(REDIRECT_URI)) {
                    parseToken(url);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldOverrideUrlLoading(view, request.getUrl().toString());
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(buildAuthUrl());
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private String buildAuthUrl() {
        return "https://oauth.vk.com/authorize"
                + "?client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI
                + "&response_type=token"
                + "&scope=" + SCOPE
                + "&revoke=1"
                + "&display=mobile";
    }

    private void parseToken(String url) {
        String fragment = url.substring(url.indexOf('#') + 1);
        Map<String, String> params = new HashMap<>();
        for (String pair : fragment.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        String token = params.get("access_token");
        String userId = params.get("user_id");
        if (token != null && userId != null) {
            Session.save(this, token, userId);
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            webView.loadUrl(buildAuthUrl());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
