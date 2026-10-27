package com.vktea.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** A fresh task prevents screens from the previous account surviving a login. */
public class TokenRevealActivity extends AppCompatActivity {
    /** Called before restoring any screen belonging to a prior account. */
    public static boolean redirectIfPending(Activity activity) {
        if (activity instanceof TokenRevealActivity || !Session.hasPendingReveal(activity)) return false;
        if (!activity.isFinishing()) TokenRevealDialog.start(activity);
        return true;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String token = Session.getToken(this);
        if (token == null || token.isEmpty()) {
            Intent intent = new Intent(this, AuthActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
        // The token is read from private preferences each time; never put it in an intent or saved state.
        TokenRevealDialog.show(this, token);
    }
}
