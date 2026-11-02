package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

/** Account notifications expose only generations and never require network access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionChangeListenerTest {
    @Test public void listenersSeeUpdatedGenerationAndCanUnsubscribe() {
        Context context = RuntimeEnvironment.getApplication();
        List<Long> observed = new ArrayList<>();
        Runnable listener = () -> observed.add(Session.generation());
        Session.addChangeListener(listener);
        try {
            long initial = Session.generation();
            Session.save(context, "synthetic-token", "42", "synthetic-secret");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, observed.size());
            assertTrue(observed.get(0) > initial);
            Session.clear(context);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, observed.size());
            assertTrue(observed.get(1) > observed.get(0));
            Session.removeChangeListener(listener);
            Session.save(context, "synthetic-other-token", "84", "synthetic-other-secret");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, observed.size());
        } finally {
            Session.removeChangeListener(listener);
            Session.clear(context);
        }
    }
}
