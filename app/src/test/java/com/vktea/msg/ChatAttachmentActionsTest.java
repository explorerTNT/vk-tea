package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatAttachmentActionsTest {
    private static ChatAttachment attachment(String type, String url) {
        return new ChatAttachment(type, "Synthetic", "", "", url, 0, 0, 0, 1, 2, "");
    }

    @Test public void supportedWebAttachmentsOpenTheExactUrlOnTap() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        String url = "https://example.invalid/doc?sig=synthetic%2Bkey&x=1";
        for (String type : new String[]{"doc", "link", "article", "wall", "wall_reply"}) {
            ChatAttachment item = attachment(type, url);
            assertTrue(ChatAttachmentActions.canOpen(item));
            assertTrue(ChatAttachmentActions.open(activity, item));
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, intent.getAction());
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertEquals(url, intent.getDataString());
        }
    }

    @Test public void unsafeOrUnavailableUrlsAndVoiceDoNotBecomeExternalIntents() {
        for (String url : new String[]{"", "file:///data/local/a", "content://private/a", "intent://host/a",
                "javascript:alert(1)", "https://user@host.invalid/a", "https:///no-host", "https://host.invalid/a b"}) {
            assertFalse(ChatAttachmentActions.canOpen(attachment("doc", url)));
        }
        assertFalse(ChatAttachmentActions.canOpen(attachment("audio_message", "https://example.invalid/voice.mp3")));
        assertFalse(ChatAttachmentActions.canOpen(null));
    }
}
