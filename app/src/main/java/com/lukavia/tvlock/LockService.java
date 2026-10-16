package com.lukavia.tvlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/**
 * Long-running service that watches for the screen turning off/on
 * and shows the PIN overlay. Locking on SCREEN_OFF means the lock is
 * already up the moment the TV wakes, so nothing flashes on screen.
 */
public class LockService extends Service {
    static final String EXTRA_LOCK_NOW = "lock_now";
    private static final String CHANNEL_ID = "tvlock";
    private static final int NOTIFICATION_ID = 1;

    private LockOverlay overlay;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a) || Intent.ACTION_SCREEN_ON.equals(a)) {
                showLock();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new LockOverlay(this);
        goForeground();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_LOCK_NOW, false)) {
            showLock();
        }
        return START_STICKY;
    }

    private void showLock() {
        if (!Settings.canDrawOverlays(this) || !Pin.isSet(this)) return;
        overlay.show();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "TV Lock", NotificationManager.IMPORTANCE_MIN));

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("TV Lock is active")
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenReceiver);
        overlay.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
