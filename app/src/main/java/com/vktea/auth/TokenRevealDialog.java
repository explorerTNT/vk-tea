package com.vktea.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.vktea.MainActivity;
import com.vktea.R;

public class TokenRevealDialog {

    public static void show(Context context, String token) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        box.setPadding(pad, (int) (pad * 0.75), pad, 0);

        TextView warning = new TextView(context);
        warning.setText(R.string.token_saved_text);
        warning.setTextSize(14);
        box.addView(warning);

        TextView tokenView = new TextView(context);
        tokenView.setText(token);
        tokenView.setTypeface(android.graphics.Typeface.MONOSPACE);
        tokenView.setTextSize(12);
        tokenView.setTextIsSelectable(true);
        tokenView.setBackgroundColor(0x22888888);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        tokenView.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        box.addView(tokenView, lp);

        TextView danger = new TextView(context);
        danger.setText(R.string.token_danger);
        danger.setTextSize(14);
        danger.setTextColor(0xFFD32F2F);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = pad / 2;
        box.addView(danger, lp2);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.token_saved_title)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(R.string.continue_btn, (d, w) -> goMain(context))
                .setNeutralButton(R.string.copy_token, null)
                .show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("token", token));
            Toast.makeText(context, R.string.token_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private static void goMain(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        if (context instanceof AuthActivity) {
            ((AuthActivity) context).finish();
        } else if (context instanceof AuthWebViewActivity) {
            ((AuthWebViewActivity) context).finish();
        }
    }
}
