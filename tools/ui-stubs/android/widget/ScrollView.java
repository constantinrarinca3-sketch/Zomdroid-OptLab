package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class ScrollView extends FrameLayout {
    public ScrollView(Context context) { super(context); }
    public void setFillViewport(boolean value) {}
    public static class LayoutParams extends FrameLayout.LayoutParams {
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
