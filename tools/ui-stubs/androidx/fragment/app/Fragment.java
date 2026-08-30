package androidx.fragment.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class Fragment {
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) { return null; }
    public void onResume() {}
    public void onDestroyView() {}
    public Context requireContext() { return new Context(); }
    public String getString(int resource) { return Integer.toString(resource); }
    public String getString(int resource, Object... args) { return Integer.toString(resource); }
}
