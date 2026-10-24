package com.vktea.share;

import static org.junit.Assert.*;
import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareAttachmentTest {
    @Test public void wallAndPhotoKeepDifferentApiObjectsAndKeysNeverEnterCanonicalLinks() {
        ShareAttachment wall = ShareAttachment.post(-41, 12, "synthetic-key");
        ShareAttachment photo = ShareAttachment.photo(-41, 12, "synthetic-key", false);
        assertEquals("wall-41_12", wall.object()); assertEquals("photo-41_12", photo.object());
        assertEquals("wall-41_12", wall.messageAttachment()); assertEquals("photo-41_12_synthetic-key", photo.messageAttachment());
        assertEquals("https://vk.com/wall-41_12", wall.canonicalLink());
        assertEquals("https://vk.com/photo-41_12", photo.canonicalLink()); assertFalse(photo.canRepost);
    }
    @Test public void unsupportedTypeAndInvalidIdentityNeverBecomeAnApiAttachment() {
        for (ShareAttachment value : new ShareAttachment[]{new ShareAttachment("https://synthetic.invalid/photo", 41, 12, "", true),
                ShareAttachment.post(0, 12, ""), ShareAttachment.post(41, 0, ""), ShareAttachment.post(Long.MIN_VALUE, 12, "")}) {
            assertFalse(value.hasIdentity()); assertFalse(value.canRepost); assertEquals("", value.object()); assertEquals("", value.messageAttachment());
        }
    }
    @Test public void parcelRoundTripPreservesKnownIdentityAndPermissionForActivityRecreation() {
        ShareAttachment source = ShareAttachment.photo(-41, 12, "synthetic-key", false);
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            ShareAttachment restored = ShareAttachment.CREATOR.createFromParcel(parcel);
            assertEquals(source.object(), restored.object()); assertEquals(source.accessKey, restored.accessKey);
            assertFalse(restored.canRepost); assertTrue(restored.hasIdentity());
        } finally { parcel.recycle(); }
    }
}
