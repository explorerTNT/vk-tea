package com.vktea.photos;

import androidx.fragment.app.FragmentActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PhotosFragmentStateTest {
    @Test public void pendingPickerSurvivesActivityRecreation() throws Exception {
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = controller.get();
        PhotosFragment fragment = PhotosFragment.newAlbumInstance(12, 34, "Альбом");
        activity.getSupportFragmentManager().beginTransaction().add(fragment, "photos").commitNow();
        Field album = PhotosFragment.class.getDeclaredField("pendingAlbum"); album.setAccessible(true); album.setLong(fragment, 34);
        Field user = PhotosFragment.class.getDeclaredField("pendingUser"); user.setAccessible(true); user.set(fragment, "12");
        controller.recreate();
        PhotosFragment restored = (PhotosFragment) controller.get().getSupportFragmentManager().findFragmentByTag("photos");
        assertNotNull(restored); assertEquals(34, album.getLong(restored)); assertEquals("12", user.get(restored));
        controller.pause().stop().destroy();
    }
}
