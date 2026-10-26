package com.vktea.msg;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.vktea.R;

import org.json.JSONObject;

/** Consistent avatar replacement for recycled dialog rows, message bubbles and chat headers. */
public final class MessageAvatars {
    interface Images {
        void clear(ImageView target);
        void load(ImageView target, String url, int placeholder);
    }
    private static final Images GLIDE = new Images() {
        @Override public void clear(ImageView target) {
            Glide.with(target.getContext().getApplicationContext()).clear(target);
            target.setImageDrawable(null);
        }
        @Override public void load(ImageView target, String url, int placeholder) {
            Glide.with(target).load(url).placeholder(placeholder).error(placeholder)
                    .circleCrop().dontAnimate().into(target);
        }
    };
    private MessageAvatars() { }

    public static void bind(ImageView target, String url, boolean group) {
        bind(target, url, group, GLIDE);
    }

    static void bind(ImageView target, String url, boolean group, Images images) {
        images.clear(target);
        int placeholder = group ? R.drawable.message_avatar_group : R.drawable.chat_avatar_placeholder;
        target.setImageResource(placeholder);
        if (url != null && !url.trim().isEmpty()) images.load(target, url.trim(), placeholder);
    }

    public static void clear(ImageView target) { clear(target, GLIDE); }

    static void clear(ImageView target, Images images) {
        images.clear(target);
        target.setImageResource(R.drawable.chat_avatar_placeholder);
    }

    /** VK responses can omit a size or contain an empty/null field rather than a usable URL. */
    public static String photoUrl(JSONObject object) {
        if (object == null) return "";
        String direct = directPhotoUrl(object);
        return direct.isEmpty() ? directPhotoUrl(object.optJSONObject("photo")) : direct;
    }

    private static String directPhotoUrl(JSONObject object) {
        if (object == null) return "";
        for (String key : new String[]{"photo_200", "photo_100", "photo_50"}) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
        }
        return "";
    }
}
