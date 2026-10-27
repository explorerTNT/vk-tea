package com.vktea.msg;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/** Keep multiline editing while allowing IMEs to show the optional send action. */
public final class ChatComposeInput extends AppCompatEditText {
    public ChatComposeInput(Context context, AttributeSet attrs) { super(context, attrs); }

    @Nullable @Override public InputConnection onCreateInputConnection(@NonNull EditorInfo info) {
        InputConnection connection = super.onCreateInputConnection(info);
        if ((getImeOptions() & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND) {
            info.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }
        return connection;
    }
}
