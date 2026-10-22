package com.vktea;

import android.app.Application;

import com.vk.id.VKID;

public class VKTeaApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        VKID.Companion.init(this);
    }
}
