package androidx.appcompat.widget;

import android.content.Context;
import android.widget.TextView;

public class SwitchCompat extends TextView {
    public interface Listener { void changed(SwitchCompat view, boolean checked); }
    public SwitchCompat(Context context) { super(context); }
    public void setChecked(boolean value) {}
    public void setEnabled(boolean value) {}
    public void setOnCheckedChangeListener(Listener listener) {}
}
