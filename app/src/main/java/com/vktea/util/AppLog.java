package com.vktea.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

public class AppLog {

    private static final int LIMIT = 80;
    private static final Deque<String> BUFFER = new ArrayDeque<>();

    public static synchronized void log(String tag, String message) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = time + " [" + tag + "] " + message;
        BUFFER.addLast(line);
        while (BUFFER.size() > LIMIT) {
            BUFFER.removeFirst();
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
}
