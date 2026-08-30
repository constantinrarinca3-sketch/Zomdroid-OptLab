package android.widget;

import android.content.Context;

public class Spinner extends AdapterView<ArrayAdapter<?>> {
    public Spinner(Context context) { super(context); }
    public void setAdapter(ArrayAdapter<?> value) {}
    public void setOnItemSelectedListener(OnItemSelectedListener value) {}
    public int getSelectedItemPosition() { return 0; }
    public void setSelection(int position, boolean animate) {}
}
