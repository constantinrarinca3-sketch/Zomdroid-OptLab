package com.google.android.material.tabs;

import android.content.Context;
import android.view.View;

public class TabLayout extends View {
    public TabLayout(Context context) { super(context); }
    public Tab newTab() { return new Tab(); }
    public void addTab(Tab tab) {}
    public Tab getTabAt(int position) { return new Tab(); }
    public int getSelectedTabPosition() { return 0; }
    public void addOnTabSelectedListener(OnTabSelectedListener listener) {}
    public static class Tab {
        public Tab setText(int resource) { return this; }
        public int getPosition() { return 0; }
        public void select() {}
    }
    public interface OnTabSelectedListener {
        void onTabSelected(Tab tab);
        void onTabUnselected(Tab tab);
        void onTabReselected(Tab tab);
    }
}
