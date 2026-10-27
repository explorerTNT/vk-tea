package com.vktea.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import com.vktea.auth.Session;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VKApiSessionTest {
    @Test public void oldQueuedRequestsAreCancelledAndCannotRegisterAfterSwitch() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(c, "synthetic-first", "42");
        long oldGeneration = Session.generation();
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder().url("https://example.invalid/").build();
        okhttp3.Call pending = client.newCall(request);
        assertTrue(VKApi.registerCall(oldGeneration, pending));
        Session.save(c, "synthetic-second", "43");
        assertTrue(pending.isCanceled());
        okhttp3.Call late = client.newCall(request);
        assertFalse(VKApi.registerCall(oldGeneration, late));
        assertTrue(late.isCanceled());
        okhttp3.Call current = client.newCall(request);
        assertTrue(VKApi.registerCall(Session.generation(), current));
        Session.clear(c);
        assertTrue(current.isCanceled());
        c.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void queuedResponseFromPreviousLoginIsNotDelivered() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(c, "first", "42", "secret");
        AtomicInteger delivered = new AtomicInteger();
        VKApi.postIfCurrent(Session.generation(), delivered::incrementAndGet);
        // Reauthenticating as the same ID must invalidate old responses too.
        Session.save(c, "second", "42", "secret");
        VKApi.postIfCurrent(Session.generation(), delivered::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, delivered.get());
        c.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }
}
