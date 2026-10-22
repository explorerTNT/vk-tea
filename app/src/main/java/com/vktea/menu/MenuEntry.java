package com.vktea.menu;

public class MenuEntry {

    public interface Callback {
        void onMenuClick(MenuEntry entry);
    }

    public final int id;
    public final int iconRes;
    public final int titleRes;

    public MenuEntry(int id, int iconRes, int titleRes) {
        this.id = id;
        this.iconRes = iconRes;
        this.titleRes = titleRes;
    }
}
