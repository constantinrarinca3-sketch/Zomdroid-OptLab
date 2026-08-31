package androidx.appcompat.app;

import android.content.Context;

public final class AlertDialog {
    public static final class Builder {
        public interface DismissListener { void dismissed(Object dialog); }
        public Builder(Context context) {}
        public Builder setTitle(int value) { return this; }
        public Builder setView(Object value) { return this; }
        public Builder setPositiveButton(int value, Object listener) { return this; }
        public Builder setOnDismissListener(DismissListener listener) { return this; }
        public AlertDialog show() { return new AlertDialog(); }
    }
}
