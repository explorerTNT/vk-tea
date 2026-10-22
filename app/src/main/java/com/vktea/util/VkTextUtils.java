package com.vktea.util;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VkTextUtils {

    public interface ClickListener {
        void onClick(String url);
    }

    private static final Pattern VK_LINK = Pattern.compile("\\[([^|\\]]+)\\|([^\\]]+)\\]");
    private static final Pattern AUTO_LINK = Pattern.compile(
            "(https?://[^\\s]+)|(#[\\w\\u0400-\\u04FF]+)|(@[a-zA-Z][\\w\\-.]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static CharSequence parse(String raw, int linkColor, ClickListener listener) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        Matcher vkLink = VK_LINK.matcher(raw);
        int last = 0;
        while (vkLink.find()) {
            builder.append(raw, last, vkLink.start());
            String url = vkLink.group(1);
            String title = vkLink.group(2);
            int start = builder.length();
            builder.append(title);
            applySpan(builder, start, builder.length(), url, linkColor, listener);
            last = vkLink.end();
        }
        builder.append(raw, last, raw.length());

        Matcher auto = AUTO_LINK.matcher(builder.toString());
        while (auto.find()) {
            int start = auto.start();
            int end = auto.end();
            if (builder.getSpans(start, end, ClickableSpan.class).length > 0) {
                continue;
            }
            String url = auto.group(1) != null ? auto.group(1)
                    : auto.group(2) != null ? "https://vk.com/search?c[q]=" + auto.group(2) + "&c[section]=statuses"
                    : "https://vk.com/" + auto.group(3).substring(1);
            applySpan(builder, start, end, url, linkColor, listener);
        }
        return builder;
    }

    private static void applySpan(SpannableStringBuilder builder, int start, int end,
                                  String url, int color, ClickListener listener) {
        ClickableSpan span = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (listener != null) {
                    listener.onClick(url);
                }
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(color);
                ds.setUnderlineText(false);
            }
        };
        builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
