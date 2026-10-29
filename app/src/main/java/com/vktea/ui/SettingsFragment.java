package com.vktea.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.BuildConfig;
import com.vktea.R;
import com.vktea.auth.AuthActivity;
import com.vktea.auth.Session;
import com.vktea.api.VKApi;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView avatar = view.findViewById(R.id.settings_avatar);
        TextView name = view.findViewById(R.id.settings_name);
        TextView status = view.findViewById(R.id.settings_status);

        VKApi.get(requireContext(), "users.get", "fields=photo_100,status", new VKApi.Callback() {
            @Override
            public void success(org.json.JSONObject response) {
                try {
                    org.json.JSONObject u = response.getJSONArray("response").getJSONObject(0);
                    requireActivity().runOnUiThread(() -> {
                        name.setText(u.optString("first_name") + " " + u.optString("last_name"));
                        status.setText(u.optString("status", "в сети"));
                        String url = u.optString("photo_100", "");
                        if (!url.isEmpty()) {
                            Glide.with(avatar).load(url)
                                    .apply(RequestOptions.circleCropTransform())
                                    .into(avatar);
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override
            public void error(Exception e) {
            }
        });

        view.findViewById(R.id.settings_logout).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_logout_confirm)
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            Session.clear(requireContext());
                            android.content.Intent intent = new android.content.Intent(requireContext(), AuthActivity.class);
                            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        android.widget.Switch darkSwitch = view.findViewById(R.id.settings_dark_theme_switch);
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        darkSwitch.setChecked(prefs.getBoolean("dark_theme", false));
        darkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("dark_theme", isChecked).apply();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    isChecked ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        });

        view.findViewById(R.id.settings_clear_cache).setOnClickListener(v -> {
            new Thread(() -> {
                Glide.get(requireContext()).clearDiskCache();
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show());
            }).start();
        });

        view.findViewById(R.id.settings_check_updates).setOnClickListener(v -> {
            if (getActivity() instanceof com.vktea.MainActivity) {
                ((com.vktea.MainActivity) getActivity()).checkForUpdatesPublic();
            }
        });

        TextView version = view.findViewById(R.id.settings_version);
        version.setText(getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
    }
}
