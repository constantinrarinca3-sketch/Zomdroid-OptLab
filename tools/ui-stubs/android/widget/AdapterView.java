package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class AdapterView<T> extends ViewGroup {
    public AdapterView(Context context) { super(context); }
    public Object getItemAtPosition(int position) { return null; }
    public interface OnItemSelectedListener {
        void onItemSelected(AdapterView<?> parent, View view, int position, long id);
        void onNothingSelected(AdapterView<?> parent);
    }
}
