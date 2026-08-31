package com.zomdroid;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import androidx.annotation.NonNull;

/** Starts a tiny helper process that replaces the launcher process deterministically. */
public final class AppProcessRestarter {
    static final String EXTRA_OLD_PID = "com.zomdroid.extra.OLD_PROCESS_PID";

    private AppProcessRestarter() {}

    public static void restart(@NonNull Context context) {
        Intent intent = new Intent(context.getApplicationContext(), ProcessRestartActivity.class)
                .putExtra(EXTRA_OLD_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.getApplicationContext().startActivity(intent);
    }
}
