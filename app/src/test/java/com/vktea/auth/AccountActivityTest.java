package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AccountActivityTest {
    @Test public void systemBarsInsetBothTopAndBottomActions() {
        AccountActivity activity = Robolectric.buildActivity(AccountActivity.class).create().get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(12, 30, 14, 40)).build();
        ViewCompat.dispatchApplyWindowInsets(root, insets);
        assertEquals(12, root.getPaddingLeft());
        assertEquals(30, root.getPaddingTop());
        assertEquals(14, root.getPaddingRight());
        assertEquals(40, root.getPaddingBottom());
    }
    @Test public void removingActiveAccountClearsOldTaskBeforeAccountPicker() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "first", "42", "secret");
        Session.save(context, "second", "73", "secret");
        // Create without resuming, so this remains an offline test.
        AccountActivity activity = Robolectric.buildActivity(AccountActivity.class).create().get();
        java.lang.reflect.Method options = AccountActivity.class.getDeclaredMethod("options", String.class, boolean.class);
        options.setAccessible(true);
        options.invoke(activity, "73", true);
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        choices.getListView().performItemClick(choices.getListView().getChildAt(0), 0,
                choices.getListView().getAdapter().getItemId(0));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(AccountActivity.class.getName(), next.getComponent().getClassName());
        assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
        assertEquals("42", Session.savedAccounts(context).get(0));
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }
}
