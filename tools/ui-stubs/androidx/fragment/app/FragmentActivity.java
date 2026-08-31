package androidx.fragment.app;

import androidx.activity.OnBackPressedDispatcher;

public class FragmentActivity {
    public OnBackPressedDispatcher getOnBackPressedDispatcher() {
        return new OnBackPressedDispatcher();
    }
}
