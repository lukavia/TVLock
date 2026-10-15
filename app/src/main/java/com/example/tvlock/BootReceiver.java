package com.example.tvlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the lock service (and locks immediately) after boot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent svc = new Intent(context, LockService.class);
        svc.putExtra(LockService.EXTRA_LOCK_NOW, true);
        context.startForegroundService(svc);
    }
}
