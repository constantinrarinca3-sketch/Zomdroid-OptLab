package android.content.res;

import android.util.DisplayMetrics;
import android.util.TypedValue;

public class Resources {
    private final DisplayMetrics metrics = new DisplayMetrics();
    public DisplayMetrics getDisplayMetrics() { return metrics; }
    public Theme newTheme() { return new Theme(); }
    public static class Theme {
        public boolean resolveAttribute(int attribute, TypedValue value, boolean refs) {
            value.data = attribute;
            return true;
        }
    }
}
