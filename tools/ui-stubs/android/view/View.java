package android.view;

import android.content.Context;
import android.graphics.drawable.Drawable;

public class View {
    public static final int VISIBLE = 0;
    public static final int GONE = 8;
    private final Context context;
    public View(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public void setEnabled(boolean value) {}
    public boolean isEnabled() { return true; }
    public void setAlpha(float value) {}
    public void setClickable(boolean value) {}
    public void setFocusable(boolean value) {}
    public void setOnClickListener(OnClickListener listener) {}
    public void setOnTouchListener(OnTouchListener listener) {}
    public void setOnKeyListener(OnKeyListener listener) {}
    public void setPadding(int left, int top, int right, int bottom) {}
    public void setMinimumHeight(int value) {}
    public void setBackground(Drawable value) {}
    public void setVisibility(int value) {}
    public void setLayoutParams(ViewGroup.LayoutParams value) {}
    public void scrollTo(int x, int y) {}
    public <T extends View> T findViewById(int id) { return null; }
    public interface OnClickListener { void onClick(View view); }
    public interface OnTouchListener { boolean onTouch(View view, MotionEvent event); }
    public interface OnKeyListener { boolean onKey(View view, int keyCode, KeyEvent event); }
}
