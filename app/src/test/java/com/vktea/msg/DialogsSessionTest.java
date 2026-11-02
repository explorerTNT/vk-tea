package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DialogsSessionTest {
    private ActivityController<DialogsFragmentTest.Host> controller;
    private DialogsFragmentTest.OfflineDialogs fragment;
    private DialogsAdapter adapter;

    @Before public void open() {
        Session.save(RuntimeEnvironment.getApplication(), "token-a", "42");
        shadowOf(Looper.getMainLooper()).idle();
        controller = Robolectric.buildActivity(DialogsFragmentTest.Host.class).setup();
        fragment = controller.get().fragment;
        adapter = (DialogsAdapter) ((RecyclerView) fragment.requireView().findViewById(R.id.dialogs_list)).getAdapter();
    }
    @After public void close() { if (controller != null) controller.close(); }
    private JSONObject history() throws Exception {
        return new JSONObject("{\"response\":{\"items\":[{\"message\":{\"user_id\":99,\"title\":\"Private A\"}}]}}");
    }
    private void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void otherAccountAndLogoutClearRowsAndIgnoreOldCallbacks() throws Exception {
        VKApi.Callback old = fragment.dialogs.get(0);
        old.success(history());
        assertEquals(1, adapter.getItemCount());
        Session.save(controller.get(), "token-b", "43");
        idle();
        assertEquals(0, adapter.getItemCount());
        assertEquals(2, fragment.dialogs.size());
        old.success(history());
        assertEquals(0, adapter.getItemCount());
        fragment.dialogs.get(1).success(history());
        assertEquals(1, adapter.getItemCount());
        Session.clear(controller.get());
        idle();
        assertEquals(0, adapter.getItemCount());
        assertEquals(2, fragment.dialogs.size());
    }

    @Test public void refreshingSameAccountKeepsRowsUntilNewHistory() throws Exception {
        VKApi.Callback old = fragment.dialogs.get(0);
        old.success(history());
        Session.save(controller.get(), "refreshed-token", "42");
        idle();
        assertEquals(1, adapter.getItemCount());
        assertEquals(2, fragment.dialogs.size());
        old.success(new JSONObject("{\"response\":{\"items\":[]}}"));
        assertEquals(1, adapter.getItemCount());
        fragment.dialogs.get(1).success(new JSONObject("{\"response\":{\"items\":[]}}"));
        assertEquals(0, adapter.getItemCount());
    }
}
