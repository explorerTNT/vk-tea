package com.vktea.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.vktea.R;

public class AuthActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        View loginButton = findViewById(R.id.auth_login_btn);
        View signupButton = findViewById(R.id.auth_signup_btn);
        View forgotButton = findViewById(R.id.auth_forgot);

        loginButton.setOnClickListener(v -> startActivity(new Intent(this, AuthWebViewActivity.class)));
        signupButton.setOnClickListener(v -> startActivity(new Intent(this, AuthWebViewActivity.class)));
        forgotButton.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.vk.com/restore"))));
    }
}
