package com.vktea.updates;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Чейнджлог из GitHub Releases -> читаемый текст для диалога обновления. */
public final class ChangelogFormatter {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private ChangelogFormatter() {
    }

    public static CharSequence format(String notes) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = notes == null ? new String[0] : notes.split("\n");
        boolean first = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("---")) continue;
            boolean header = line.startsWith("#");
            if (header) line = line.replaceFirst("^#+\\s*", "");
            line = line.startsWith("- ") ? "• " + line.substring(2) : line;

            if (!first) out.append("\n\n");
            first = false;
            int start = out.length();
            Matcher bold = BOLD.matcher(line);
            StringBuilder plain = new StringBuilder();
            int copied = 0;
            while (bold.find()) {
                plain.append(line, copied, bold.start()).append(bold.group(1));
                copied = bold.end();
            }
            plain.append(line, copied, line.length());
            out.append(plain);
            int end = out.length();
            if (header) {
                out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new AbsoluteSizeSpan(16, true), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("•")) {
                // маркированные строки оставляем обычными, но чуть плотнее
                out.setSpan(new AbsoluteSizeSpan(14, true), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }
}
