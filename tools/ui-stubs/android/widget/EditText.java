package android.widget;

import android.content.Context;

public class EditText extends TextView {
    public EditText(Context context) { super(context); }
    public void setSingleLine(boolean value) {}
    public void setHint(CharSequence value) {}
    public CharSequence getText() { return ""; }
}
