package com.vktea.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vktea.R;

public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_ICON = "icon";

    public static PlaceholderFragment newInstance(String title, int iconRes) {
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putInt(ARG_ICON, iconRes);
        PlaceholderFragment fragment = new PlaceholderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_placeholder, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        ((TextView) view.findViewById(R.id.placeholder_title)).setText(args.getString(ARG_TITLE));
        ((ImageView) view.findViewById(R.id.placeholder_icon)).setImageResource(args.getInt(ARG_ICON));
    }
}
