package com.vktea.auth;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

public class AuthLog {

    private static final int LIMIT = 60;
    private static final Deque<String> BUFFER = new ArrayDeque<>();

    public static synchronized void log(String event, String detail) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " " + event + (detail == null || detail.isEmpty() ? "" : " | " + detail);
        BUFFER.addLast(line);
        while (BUFFER.size() > LIMIT) {
            BUFFER.removeFirst();
        }
    }

    public static void persist(Context context) {
        try {
            File file = new File(context.getFilesDir(), "auth_log.txt");
            FileWriter writer = new FileWriter(file, false);
            for (String line : BUFFER) {
                writer.write(line + "\n");
            }
            writer.close();
        } catch (Exception ignored) {
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = BUFFER.descendingIterator();
        while (it.hasNext()) {
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    public static String maskUrl(String url) {
        if (url == null) {
            return null;
        }
        String masked = url
                .replaceAll("password=[^&]*", "password=***")
                .replaceAll("username=[^&]*", "username=***")
                .replaceAll("client_secret=[^&]*", "client_secret=***")
                .replaceAll("trusted_hash=[^&]*", "trusted_hash=***")
                .replaceAll("captcha_key=[^&]*", "captcha_key=***")
                .replaceAll("vk1\\.a\\.[A-Za-z0-9_\\-]+", "vk1.a.***")
                .replaceAll("vk2\\.a\\.[A-Za-z0-9_\\-]+", "vk2.a.***");
        if (masked.length() > 1400) {
            return masked.substring(0, 1400) + "...";
        }
        return masked;
    }

    public static String maskBody(String body) {
        if (body == null) {
            return "";
        }
        String masked = body
                .replaceAll("\"access_token\":\"[^\"]+\"", "\"access_token\":\"***\"")
                .replaceAll("\"secret\":\"[^\"]+\"", "\"secret\":\"***\"")
                .replaceAll("\"trusted_hash\":\"[^\"]+\"", "\"trusted_hash\":\"***\"")
                .replaceAll("\"refresh_token\":\"[^\"]+\"", "\"refresh_token\":\"***\"")
                .replaceAll("vk1\\.a\\.[A-Za-z0-9_\\-]+", "vk1.a.***")
                .replaceAll("vk2\\.a\\.[A-Za-z0-9_\\-]+", "vk2.a.***");
        if (masked.length() > 500) {
            return masked.substring(0, 500) + "...";
        }
        return masked;
    }
}
