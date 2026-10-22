package com.vktea.menu;

public class MenuEntry {

    public interface Callback {
        void onMenuClick(MenuEntry entry);
    }

    public final int id;
    public final int iconRes;
    public final int titleRes;
    public int counter;

    public MenuEntry(int id, int iconRes, int titleRes) {
        this(id, iconRes, titleRes, 0);
    }

    public MenuEntry(int id, int iconRes, int titleRes, int counter) {
        this.id = id;
        this.iconRes = iconRes;
        this.titleRes = titleRes;
        this.counter = counter;
    }
}
