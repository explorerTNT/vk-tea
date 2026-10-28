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
        String masked = url.replaceAll("password=[^&]*", "password=***")
                .replaceAll("client_secret=[^&]*", "client_secret=***");
        if (masked.length() > 1400) {
            return masked.substring(0, 1400) + "...";
        }
        return masked;
    }
}
