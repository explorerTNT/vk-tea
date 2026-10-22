package com.vktea.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.vk.id.AccessToken;
import com.vk.id.VKID;
import com.vk.id.auth.VKIDAuthCallback;
import com.vk.id.auth.VKIDAuthFail;
import com.vktea.MainActivity;
import com.vktea.R;

public class AuthActivity extends AppCompatActivity implements VKIDAuthCallback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        View loginButton = findViewById(R.id.auth_login_btn);
        View signupButton = findViewById(R.id.auth_signup_btn);
        View forgotButton = findViewById(R.id.auth_forgot);

        loginButton.setOnClickListener(v -> startVkIdAuth());
        signupButton.setOnClickListener(v -> startVkIdAuth());
        forgotButton.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.vk.com/restore"))));
    }

    private void startVkIdAuth() {
        VKID.Companion.getInstance().authorize(this, this);
    }

    @Override
    public void onAuth(AccessToken accessToken) {
        Session.save(this, accessToken.getToken(), String.valueOf(accessToken.getUserID()));
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onFail(VKIDAuthFail fail) {
        if (!(fail instanceof VKIDAuthFail.Canceled)) {
            Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
