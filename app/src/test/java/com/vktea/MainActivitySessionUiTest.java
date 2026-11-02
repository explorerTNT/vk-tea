package com.vktea;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.menu.LeftMenuAdapter;
import com.vktea.menu.MenuEntry;
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
import org.robolectric.util.ReflectionHelpers;
import java.util.List;

/** Synthetic session transitions; no real account or external network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {MainActivityCountersTest.Api.class, MainActivityCountersTest.Updates.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivitySessionUiTest {
    private Context context;
    private ActivityController<MainActivity> controller;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "synthetic-account-a", "101");
        MainActivityCountersTest.Api.profiles.clear(); MainActivityCountersTest.Api.counters.clear();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new ComponentName(context, AudioPlaybackService.class));
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible(); idle();
    }
    @After public void close() {
        controller.close(); context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit(); idle();
    }
    @Test public void tokenRefreshKeepsHeaderAndBadgeButAccountChangeClearsThem() throws Exception {
        MainActivityCountersTest.Api.profiles.get(0).success(profile(101, "Account A", 3, 2));
        MainActivityCountersTest.Api.counters.get(0).success(counters(3, 2)); idle();
        LeftMenuAdapter adapter = ReflectionHelpers.getField(controller.get(), "menuAdapter");
        RecyclerView menu = controller.get().findViewById(R.id.left_menu);
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(menu, adapter.getItemViewType(1));
        adapter.onBindViewHolder(header, 1);
        assertEquals("Account A User", text(header, R.id.menu_header_name));
        assertEquals("5", badge().getText().toString());
        Session.save(context, "synthetic-refreshed-a", "101"); idle();
        adapter.onBindViewHolder(header, 1);
        assertEquals("Account A User", text(header, R.id.menu_header_name));
        assertEquals("5", badge().getText().toString());
        assertEquals(2, MainActivityCountersTest.Api.profiles.size());
        assertEquals(2, MainActivityCountersTest.Api.counters.size());
        ((ImageView) header.itemView.findViewById(R.id.menu_header_avatar)).setImageResource(R.drawable.ic_menu_friends);
        Session.save(context, "synthetic-account-b", "202"); idle();
        adapter.onBindViewHolder(header, 1);
        assertEquals(controller.get().getString(R.string.menu_user_name), text(header, R.id.menu_header_name));
        assertEquals(controller.get().getString(R.string.menu_user_status), text(header, R.id.menu_header_status));
        assertNull(((ImageView) header.itemView.findViewById(R.id.menu_header_avatar)).getDrawable());
        assertNotNull(((ImageView) header.itemView.findViewById(R.id.menu_header_cover)).getDrawable());
        assertEquals(View.GONE, badge().getVisibility());
        List<MenuEntry> entries = ReflectionHelpers.getField(controller.get(), "menu");
        for (MenuEntry entry : entries) assertEquals(0, entry.counter);
        MainActivityCountersTest.Api.profiles.get(1).success(profile(101, "Old", 7, 7)); idle();
        adapter.onBindViewHolder(header, 1);
        assertEquals(controller.get().getString(R.string.menu_user_name), text(header, R.id.menu_header_name));
    }
    @Test public void sessionChangeWhileStoppedClearsHeaderWhenActivityResumes() throws Exception {
        MainActivityCountersTest.Api.profiles.get(0).success(profile(101, "Account A", 1, 1)); idle();
        controller.pause().stop(); Session.save(context, "synthetic-account-b", "202"); idle();
        controller.start().resume(); idle();
        LeftMenuAdapter adapter = ReflectionHelpers.getField(controller.get(), "menuAdapter");
        RecyclerView menu = controller.get().findViewById(R.id.left_menu);
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(menu, adapter.getItemViewType(1));
        adapter.onBindViewHolder(header, 1);
        assertEquals(controller.get().getString(R.string.menu_user_name), text(header, R.id.menu_header_name));
        assertEquals(View.GONE, badge().getVisibility());
    }
    @Test public void logoutClearsHeaderAndBadgeWithoutStartingNewRequests() throws Exception {
        MainActivityCountersTest.Api.profiles.get(0).success(profile(101, "Account A", 3, 2));
        MainActivityCountersTest.Api.counters.get(0).success(counters(3, 2)); idle();
        Session.clear(context); idle();
        LeftMenuAdapter adapter = ReflectionHelpers.getField(controller.get(), "menuAdapter");
        RecyclerView menu = controller.get().findViewById(R.id.left_menu);
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(menu, adapter.getItemViewType(1));
        adapter.onBindViewHolder(header, 1);
        assertEquals(controller.get().getString(R.string.menu_user_name), text(header, R.id.menu_header_name));
        assertEquals(View.GONE, badge().getVisibility());
        assertEquals(1, MainActivityCountersTest.Api.profiles.size());
        assertEquals(1, MainActivityCountersTest.Api.counters.size());
    }
    private TextView badge() { return controller.get().findViewById(R.id.toolbar_badge); }
    private static String text(RecyclerView.ViewHolder holder, int id) { return ((TextView) holder.itemView.findViewById(id)).getText().toString(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static JSONObject profile(long id, String name, int messages, int notifications) throws Exception {
        return new JSONObject().put("response", new org.json.JSONArray().put(new JSONObject().put("id", id)
                .put("first_name", name).put("last_name", "User").put("status", "Synthetic status")
                .put("counters", new JSONObject().put("friends", 4).put("groups", 1)
                        .put("messages", messages).put("notifications", notifications))));
    }
    private static JSONObject counters(int messages, int notifications) throws Exception {
        return new JSONObject().put("response", new JSONObject().put("messages", messages).put("notifications", notifications));
    }
}
