package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.TimeUnit;

/** Real parent interception, including a child which disallows interception on DOWN. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoDismissLayoutTest {
    private ActivityController<Activity> host;
    private PhotoDismissLayout layout;
    private View image;
    private boolean fit;
    private int dismisses, childCancels, multiTouches, touchEnds;
    private long down;

    @Before public void setup() {
        host = Robolectric.buildActivity(Activity.class).setup().visible();
        layout = new PhotoDismissLayout(host.get(), null);
        image = new View(host.get());
        layout.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) childCancels++;
            return true;
        });
        fit = true;
        layout.bind(image, new PhotoDismissLayout.Listener() {
            @Override public boolean canDismiss() { return fit; }
            @Override public void onProgress(float fraction) { }
            @Override public void onDismiss() { dismisses++; }
            @Override public void onMultiTouch() { multiTouches++; }
            @Override public void onTouchEnd() { touchEnds++; }
        });
        host.get().setContentView(layout);
        layout.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 400, 800);
        down = SystemClock.uptimeMillis();
    }

    @After public void close() { host.close(); }

    @Test public void bothVerticalDirectionsDismissAndCancelChildInsteadOfTogglingChrome() {
        for (int direction : new int[]{-1, 1}) {
            layout.reset();
            send(MotionEvent.ACTION_DOWN, 200, 400, 0);
            send(MotionEvent.ACTION_MOVE, 200, 400 + direction * 240, 350);
            assertEquals(direction * 240, image.getTranslationY(), .01f);
            send(MotionEvent.ACTION_UP, 200, 400 + direction * 240, 650);
            idle();
            assertEquals(direction == -1 ? 1 : 2, dismisses);
        }
        assertEquals(2, childCancels);
    }

    @Test public void shortSlowDragAndCancelReturnToOriginalPositionWithoutClosing() {
        for (int end : new int[]{MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
            send(MotionEvent.ACTION_DOWN, 200, 400, 0);
            send(MotionEvent.ACTION_MOVE, 200, 470, 350);
            assertTrue(image.getTranslationY() > 0);
            send(end, 200, 470, 700);
            idle();
            assertEquals(0, dismisses);
            assertEquals(0, image.getTranslationY(), .01f);
            assertEquals(1, image.getAlpha(), .01f);
        }
    }

    @Test public void horizontalSequenceNeverTurnsIntoVerticalDismiss() {
        send(MotionEvent.ACTION_DOWN, 200, 400, 0);
        send(MotionEvent.ACTION_MOVE, 350, 410, 100);
        send(MotionEvent.ACTION_MOVE, 350, 700, 300);
        send(MotionEvent.ACTION_UP, 350, 700, 500);
        idle();
        assertEquals(0, dismisses);
        assertEquals(0, childCancels);
        assertEquals(0, image.getTranslationY(), .01f);
    }

    @Test public void zoomedPanIsDeliveredToPhoto() {
        fit = false;
        send(MotionEvent.ACTION_DOWN, 200, 400, 0);
        send(MotionEvent.ACTION_MOVE, 200, 700, 100);
        send(MotionEvent.ACTION_UP, 200, 700, 200);
        idle();
        assertEquals(0, dismisses);
        assertEquals(0, childCancels);
    }

    @Test public void multiPointerSequenceCannotCloseEvenAfterReturningToOneFinger() {
        send(MotionEvent.ACTION_DOWN, 200, 400, 0);
        pointerDown();
        assertEquals(1, multiTouches);
        assertTrue(layout.isMultiTouchActive());
        send(MotionEvent.ACTION_MOVE, 200, 700, 200);
        send(MotionEvent.ACTION_UP, 200, 700, 400);
        idle();
        assertEquals(0, dismisses);
        assertEquals(0, image.getTranslationY(), .01f);
        assertEquals(1, touchEnds);
        assertFalse(layout.isMultiTouchActive());
    }

    @Test public void resetCancelsPendingDismissAnimation() {
        send(MotionEvent.ACTION_DOWN, 200, 400, 0);
        send(MotionEvent.ACTION_MOVE, 200, 700, 100);
        send(MotionEvent.ACTION_UP, 200, 700, 200);
        layout.reset();
        idle();
        assertEquals(0, dismisses);
        assertEquals(0, image.getTranslationY(), .01f);
    }

    private void send(int action, float x, float y, long elapsed) {
        MotionEvent event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0);
        layout.dispatchTouchEvent(event);
        event.recycle();
    }

    private void pointerDown() {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 160 + i * 80;
            coords[i].y = 400;
            coords[i].pressure = coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_POINTER_DOWN | (1 << 8),
                2, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        layout.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); }
}
