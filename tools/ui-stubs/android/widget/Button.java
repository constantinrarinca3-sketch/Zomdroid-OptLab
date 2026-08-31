package android.widget;

import android.content.Context;

public class Button extends TextView {
    public interface Listener { void clicked(Button view); }
    public Button(Context context) { super(context); }
    public void setOnClickListener(Listener listener) {}
}
