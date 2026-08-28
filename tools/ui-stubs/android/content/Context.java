package android.content;

import android.content.res.Resources;

public class Context {
    private final Resources resources = new Resources();
    public String getString(int id) { return Integer.toString(id); }
    public Resources getResources() { return resources; }
}
