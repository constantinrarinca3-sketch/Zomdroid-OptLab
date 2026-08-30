package androidx.core.content;

import android.content.Context;
import android.graphics.drawable.Drawable;

public final class ContextCompat {
    public static Drawable getDrawable(Context context, int resource) { return new Drawable(); }
    public static int getColor(Context context, int resource) { return resource; }
}
