package android.content.res;

import android.util.DisplayMetrics;

public final class Resources {
    private final DisplayMetrics metrics = new DisplayMetrics();
    public DisplayMetrics getDisplayMetrics() { return metrics; }
}
