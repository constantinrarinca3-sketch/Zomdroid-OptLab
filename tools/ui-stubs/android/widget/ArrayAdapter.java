package android.widget;

import android.content.Context;

import java.util.Collection;

public class ArrayAdapter<T> {
    public ArrayAdapter(Context context, int resource, T[] values) {}
    public void setDropDownViewResource(int resource) {}
    public int getPosition(T value) { return 0; }
    public T getItem(int position) { return null; }
    public int getCount() { return 0; }
    public void clear() {}
    public void addAll(Collection<? extends T> values) {}
    public void notifyDataSetChanged() {}
}
