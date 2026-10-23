package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;

import org.json.JSONArray;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.FormBody;
import okhttp3.HttpUrl;

/** UI regressions use only synthetic data and an injected client with no transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AuthActivityTest {
    private ActivityController<TestAuthActivity> controller;
    private AuthAttemptViewModel model;
    private FakeAuthClient client;

    @Before
    public void prepareOfflineClient() throws Exception {
        clearPreferences();
        ShadowLog.clear();
        client = new FakeAuthClient();
        TestAuthActivity.client = client;
    }

    @After
    public void releaseWorkerAndDestroyActivity() throws Exception {
        client.release.countDown();
        if (model != null) {
            await(() -> !model.isBusy());
        }
        for (Dialog dialog : new ArrayList<>(ShadowDialog.getShownDialogs())) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
        if (controller != null) {
            controller.close();
        }
        TestAuthActivity.client = null;
        clearPreferences();
    }

    @Test
    public void openingLoginDoesNotSendAnyAuthenticationRequest() {
        launch();
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        assertEquals(0, client.totalCalls());
        assertFalse(model.isBusy());
        assertTrue(activity().findViewById(R.id.auth_login_btn).isEnabled());
        assertTrue(activity().findViewById(R.id.auth_token_btn).isEnabled());
        assertEquals(View.GONE, activity().findViewById(R.id.auth_status).getVisibility());
    }

    @Test
    public void repeatedTapsKeepOneRequestAndDisableCredentialControls() throws Exception {
        client.block();
        launch();
        fillCredentials();
        activity().findViewById(R.id.auth_login_btn).performClick();
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));

        activity().findViewById(R.id.auth_login_btn).performClick();
        activity().findViewById(R.id.auth_token_btn).performClick();

        assertEquals(1, client.passwordCalls.get());
        assertEquals(1, client.totalCalls());
        assertCredentialControlsEnabled(false);
        client.release.countDown();
        await(() -> !model.isBusy());
        assertCredentialControlsEnabled(true);
        assertEquals(1, client.totalCalls());
    }

    @Test
    public void rotationKeepsPendingRequestAndDeliversItsResultToTheNewOwnerOnce()
            throws Exception {
        client.block();
        launch();
        fillCredentials();
        activity().findViewById(R.id.auth_login_btn).performClick();
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));
        AuthAttemptViewModel retained = model;

        controller.recreate();
        model = requests();

        assertSame(retained, model);
        assertCredentialControlsEnabled(false);
        assertEquals(1, client.totalCalls());
        client.release.countDown();
        await(() -> status().equals(activity().getString(R.string.auth_rejected)));
        assertCredentialControlsEnabled(true);
        assertNull(model.getResponse().getValue());
        assertNull(model.takeResponse());

        controller.recreate();
        model = requests();
        assertSame(retained, model);
        assertNull(model.getResponse().getValue());
        assertEquals(1, client.totalCalls());
    }

    @Test
    public void returningFromValidationNeverSubmitsCredentialsForEitherResultCode() {
        launch();
        fillCredentials();

        activity().onActivityResult(1, Activity.RESULT_CANCELED, new Intent());
        activity().onActivityResult(1, Activity.RESULT_OK, new Intent());
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES);

        assertEquals(0, client.totalCalls());
        assertEquals(activity().getString(R.string.auth_validation_returned), status());
        assertFalse(model.isBusy());
    }

    @Test
    public void floodResponseShowsSafeStatusWithoutRetryOrRawResponseLogs() throws Exception {
        String privateMarker = "SYNTHETIC_PRIVATE_FLOOD_RESPONSE";
        client.passwordResult = AuthResponse.password(400, new JSONObject()
                .put("error", "flood_control").put("error_description", privateMarker));
        launch();
        fillCredentials();

        activity().findViewById(R.id.auth_login_btn).performClick();
        await(() -> status().equals(activity().getString(R.string.auth_rate_limited)));
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES);

        assertEquals(1, client.totalCalls());
        assertFalse(model.isBusy());
        assertEquals(activity().getString(R.string.auth_rate_limited), status());
        assertFalse(status().contains(privateMarker));
        assertNoLogContains(privateMarker);
        assertNull(model.getResponse().getValue());
    }

    @Test
    public void passwordSuccessPreservesSigningSecretAndConsumesResponseBeforeRecreation()
            throws Exception {
        client.passwordResult = AuthResponse.password(200, new JSONObject()
                .put("access_token", "synthetic-password-token").put("user_id", 42)
                .put("secret", "synthetic-password-secret"));
        launch();
        fillCredentials();

        activity().findViewById(R.id.auth_login_btn).performClick();
        await(() -> Session.isAuthorized(activity()));

        assertEquals("synthetic-password-token", Session.getToken(activity()));
        assertEquals("42", Session.getUserId(activity()));
        assertEquals("synthetic-password-secret", Session.getSecret(activity()));
        assertEquals("", ((EditText) activity().findViewById(R.id.auth_pass)).getText().toString());
        assertNull(model.takeResponse());
        Dialog firstSuccess = ShadowDialog.getLatestDialog();
        assertNotNull(firstSuccess);
        firstSuccess.dismiss();

        controller.recreate();
        model = requests();

        assertEquals(1, client.totalCalls());
        assertNull(model.getResponse().getValue());
        assertSame(firstSuccess, ShadowDialog.getLatestDialog());
        assertEquals("synthetic-password-secret", Session.getSecret(activity()));
    }

    @Test
    public void manualTokenSuccessPreservesTheSuppliedSigningSecret() throws Exception {
        launch();
        activity().findViewById(R.id.auth_token_btn).performClick();
        AlertDialog entry = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(entry);
        EditText tokenInput = findInput(entry.getWindow().getDecorView());
        assertNotNull(tokenInput);
        tokenInput.setText("synthetic-manual-token synthetic-manual-secret");

        entry.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        await(() -> Session.isAuthorized(activity()));

        assertEquals(1, client.tokenCalls.get());
        assertEquals(1, client.totalCalls());
        assertEquals("synthetic-manual-token", Session.getToken(activity()));
        assertEquals("synthetic-manual-secret", Session.getSecret(activity()));
        assertEquals("73", Session.getUserId(activity()));
        assertNull(model.getResponse().getValue());
        assertNull(model.takeResponse());
    }

    private void launch() {
        controller = Robolectric.buildActivity(TestAuthActivity.class).setup();
        model = requests();
    }

    private TestAuthActivity activity() {
        return controller.get();
    }

    private AuthAttemptViewModel requests() {
        return new ViewModelProvider(activity()).get(AuthAttemptViewModel.class);
    }

    private void fillCredentials() {
        ((EditText) activity().findViewById(R.id.auth_login)).setText("synthetic-user@example.invalid");
        ((EditText) activity().findViewById(R.id.auth_pass)).setText("synthetic-password");
    }

    private void assertCredentialControlsEnabled(boolean enabled) {
        for (int id : new int[]{R.id.auth_login_btn, R.id.auth_token_btn,
                R.id.auth_login, R.id.auth_pass}) {
            assertEquals("Credential control " + id, enabled, activity().findViewById(id).isEnabled());
        }
    }

    private String status() {
        return ((TextView) activity().findViewById(R.id.auth_status)).getText().toString();
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertNoLogContains(String marker) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(String.valueOf(item.msg).contains(marker));
            if (item.throwable != null) {
                assertFalse(item.throwable.toString().contains(marker));
            }
        }
    }

    private static void clearPreferences() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue("Synthetic authentication did not complete", condition.getAsBoolean());
    }

    public static class TestAuthActivity extends AuthActivity {
        static FakeAuthClient client;

        @Override
        protected AuthHttpClient createAuthClient() {
            if (client == null) {
                throw new IllegalStateException("Offline authentication client was not installed");
            }
            return client;
        }
    }

    static class FakeAuthClient extends AuthHttpClient {
        final AtomicInteger passwordCalls = new AtomicInteger();
        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger vkIdCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile AuthResponse passwordResult;
        final JSONObject tokenResult;

        FakeAuthClient() throws Exception {
            passwordResult = AuthResponse.password(400,
                    new JSONObject().put("error", "invalid_grant"));
            tokenResult = new JSONObject().put("response",
                    new JSONArray().put(new JSONObject().put("id", 73)));
        }

        void block() {
            release = new CountDownLatch(1);
        }

        int totalCalls() {
            return passwordCalls.get() + tokenCalls.get() + vkIdCalls.get();
        }

        @Override
        AuthResponse password(HttpUrl ignored) {
            passwordCalls.incrementAndGet();
            return finish(AuthResponse.Method.PASSWORD, passwordResult);
        }

        @Override
        AuthResponse token(String token, String secret) {
            tokenCalls.incrementAndGet();
            return finish(AuthResponse.Method.TOKEN,
                    AuthResponse.token(200, tokenResult, token, secret));
        }

        @Override
        AuthResponse vkId(String ignoredEndpoint, FormBody ignoredForm) {
            vkIdCalls.incrementAndGet();
            return finish(AuthResponse.Method.VK_ID, AuthResponse.failure(AuthResponse.Method.VK_ID));
        }

        private AuthResponse finish(AuthResponse.Method method, AuthResponse result) {
            entered.countDown();
            try {
                return release.await(5, TimeUnit.SECONDS) ? result : AuthResponse.failure(method);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return AuthResponse.failure(method);
            }
        }
    }
}
