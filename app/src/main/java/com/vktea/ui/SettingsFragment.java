package com.vktea.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.vktea.R;
import com.vktea.auth.AuthActivity;
import com.vktea.auth.Session;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;

        TextView account = new TextView(requireContext());
        account.setText(R.string.settings_account);
        account.setTextSize(13);
        account.setTextColor(0xFF78797A);
        int padH = (int) (16 * density);
        int padTop = (int) (20 * density);
        account.setPadding(padH, padTop, padH, (int) (8 * density));
        root.addView(account);

        TextView logout = new TextView(requireContext());
        logout.setText(R.string.settings_logout);
        logout.setTextSize(16);
        logout.setTextColor(0xFFD32F2F);
        logout.setPadding(padH, (int) (14 * density), padH, (int) (14 * density));
        android.util.TypedValue ripple = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true);
        logout.setBackgroundResource(ripple.resourceId);
        logout.setOnClickListener(v -> confirmLogout());
        root.addView(logout);

        return root;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_logout_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Session.clear(requireContext());
                    Intent intent = new Intent(requireContext(), AuthActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
