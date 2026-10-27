package com.vktea.msg;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.vktea.R;

import java.net.URI;
import java.util.Locale;

/** Opens external web-backed attachments only after an explicit tap. */
public final class ChatAttachmentActions {
    private ChatAttachmentActions() { }

    public static boolean canOpen(ChatAttachment attachment) {
        if (attachment == null) return false;
        switch (attachment.type) {
            case "doc": case "link": case "wall": case "wall_reply": case "article":
                return safeWebUri(attachment.url) != null;
            default:
                return false;
        }
    }

    /** Returns true when this helper handled the attachment, even if no app can open it. */
    public static boolean open(Context context, ChatAttachment attachment) {
        if (!canOpen(attachment)) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, safeWebUri(attachment.url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.chat_attachment_open_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private static Uri safeWebUri(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
