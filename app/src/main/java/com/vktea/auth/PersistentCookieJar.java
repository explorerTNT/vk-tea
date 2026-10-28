package com.vktea.auth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

class PersistentCookieJar implements CookieJar {

    private final SharedPreferences prefs;

    PersistentCookieJar(Context context) {
        prefs = context.getSharedPreferences("auth_cookies", Context.MODE_PRIVATE);
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Cookie cookie : cookies) {
            editor.putString(cookie.name(), cookie.value() + "|" + cookie.domain() + "|" + cookie.path());
        }
        editor.apply();
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            String[] parts = ((String) value).split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            try {
                Cookie cookie = new Cookie.Builder()
                        .name(entry.getKey())
                        .value(parts[0])
                        .domain(parts[1])
                        .path(parts[2])
                        .build();
                if (cookie.matches(url)) {
                    result.add(cookie);
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}
