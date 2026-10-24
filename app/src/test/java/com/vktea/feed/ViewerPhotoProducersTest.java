package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.widget.LinearLayout;

import com.vktea.R;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewerPhotoProducersTest {
    @Test public void feedMainRepostAndPhotoFeedKeepTheirOwnPhotoIdentityInsteadOfWallPostId() throws Exception {
        Post wall = parse("{\"source_id\":42,\"post_id\":999,\"attachments\":[{\"type\":\"photo\",\"photo\":"
                + photo(11, -91, "main") + "}],\"copy_history\":[{\"id\":888,\"from_id\":42,\"attachments\":["
                + "{\"type\":\"photo\",\"photo\":" + photo(22, -92, "repost") + "}]}]}");
        assertEquals(11, wall.photoDetails.get(0).id);
        assertEquals(-91, wall.photoDetails.get(0).ownerId);
        assertEquals(22, wall.repostPhotoDetails.get(0).id);
        assertEquals("repost-key", wall.repostPhotoDetails.get(0).accessKey);
        Post feed = parse("{\"type\":\"photo\",\"source_id\":42,\"photos\":{\"items\":["
                + photo(33, -93, "first") + "," + photo(44, -94, "second") + "]}}");
        assertEquals(2, feed.photoUrls.size());
        assertEquals(33, feed.photoDetails.get(0).id);
        assertEquals(44, feed.photoDetails.get(1).id);
        assertEquals(feed.photoUrls.get(1), feed.photoDetails.get(1).url);
    }

    @Test public void nestedCardPhotoAndAlbumThumbnailClicksPropagatePhotoMetadata() throws Exception {
        Post post = parse("{\"source_id\":42,\"attachments\":[{\"type\":\"link\",\"link\":{\"title\":\"Link\","
                + "\"url\":\"https://example.invalid\",\"photo\":" + photo(51, -95, "link") + "}},"
                + "{\"type\":\"album\",\"album\":{\"id\":999,\"owner_id\":42,\"thumbs\":["
                + photo(61, -96, "album-a") + "," + photo(62, -97, "album-b") + "]}}]}");
        assertEquals(51, post.cards.get(0).imagePhoto.id);
        assertEquals(62, post.cards.get(1).photoDetails.get(1).id);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            host.get().setTheme(R.style.Theme_VKTea);
            LinearLayout container = new LinearLayout(host.get());
            CardsRenderer.render(container, post, 0xff4774a8);
            ((LinearLayout) container.getChildAt(0)).getChildAt(0).performClick();
            Intent link = shadowOf(host.get()).getNextStartedActivity();
            ArrayList<ViewerPhoto> first = link.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
            assertEquals(51, first.get(0).id);
            LinearLayout album = (LinearLayout) container.getChildAt(1);
            ((LinearLayout) album.getChildAt(1)).getChildAt(1).performClick();
            Intent opened = shadowOf(host.get()).getNextStartedActivity();
            ArrayList<ViewerPhoto> photos = opened.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
            assertEquals(2, photos.size());
            assertEquals(1, opened.getIntExtra("index", -1));
            assertEquals(62, photos.get(1).id);
            assertEquals(-97, photos.get(1).ownerId);
            assertEquals("album-b-key", photos.get(1).accessKey);
        }
    }

    private static String photo(long id, long owner, String name) {
        return "{\"id\":" + id + ",\"owner_id\":" + owner + ",\"access_key\":\"" + name
                + "-key\",\"photo_604\":\"file:///synthetic-" + name + ".png\"}";
    }

    private static Post parse(String item) throws Exception {
        NewsFeedFragment fragment = new NewsFeedFragment();
        Method sources = NewsFeedFragment.class.getDeclaredMethod("parseSources", JSONObject.class);
        sources.setAccessible(true);
        Object map = sources.invoke(fragment, new JSONObject("{\"profiles\":[{\"id\":42,\"first_name\":\"Synthetic\"}]}"));
        Method parse = NewsFeedFragment.class.getDeclaredMethod("parsePost", JSONObject.class, Map.class);
        parse.setAccessible(true);
        return (Post) parse.invoke(fragment, new JSONObject(item), map);
    }
}
