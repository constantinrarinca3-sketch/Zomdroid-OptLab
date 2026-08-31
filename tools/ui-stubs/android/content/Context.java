package android.content;

import android.content.res.Resources;

public class Context {
    private final Resources resources = new Resources();
    public String getString(int id) { return Integer.toString(id); }
    public String getString(int id, Object... args) { return Integer.toString(id); }
    public Resources getResources() { return resources; }
    public Resources.Theme getTheme() { return resources.newTheme(); }
}
