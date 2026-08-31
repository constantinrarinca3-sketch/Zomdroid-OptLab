package com.zomdroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

/** Replaces the normal app process without relying on an alarm owned by that process. */
public final class ProcessRestartActivity extends Activity {
    private static final String LOG_TAG = "ZD-OPT-RESTART";

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        final int oldPid = getIntent().getIntExtra(AppProcessRestarter.EXTRA_OLD_PID, -1);
        new Thread(() -> replaceProcess(oldPid), "zomdroid-process-restart").start();
    }

    private void replaceProcess(int oldPid) {
        if (oldPid > 0 && oldPid != Process.myPid()) {
            Process.killProcess(oldPid);
        }
        SystemClock.sleep(250L);
        runOnUiThread(() -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) {
                Log.e(LOG_TAG, "Package launch intent is unavailable");
                finishAndRemoveTask();
                terminateHelperLater();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(launch);
            finishAndRemoveTask();
            terminateHelperLater();
        });
    }

    private static void terminateHelperLater() {
        new Thread(() -> {
            SystemClock.sleep(500L);
            Process.killProcess(Process.myPid());
        }, "zomdroid-restart-helper-exit").start();
    }
}
