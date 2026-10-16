package com.lukavia.tvlock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Launcher screen: check permissions, lock now, or set/change the PIN.
 * PINs are entered with the same blind D-pad pad as the lock screen.
 */
public class SetupActivity extends Activity {
    private enum Step { CURRENT, NEW, CONFIRM }

    private View menu;
    private TextView status;
    private Button setPin;

    private PinPadView pad;
    private Step step;
    private String newPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        menu = buildMenu();
        showMenu();
        startLockService(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildMenu() {
        int pad = (int) (48 * getResources().getDisplayMetrics().density);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(pad, pad / 2, pad, pad / 2);

        TextView title = new TextView(this);
        title.setText("TV Lock");
        title.setTextSize(32);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad / 4, 0, pad / 4);

        setPin = new Button(this);
        setPin.setOnClickListener(v -> startPinFlow());

        Button lockNow = new Button(this);
        lockNow.setText("Lock now");
        lockNow.setOnClickListener(v -> {
            startLockService(true);
            moveTaskToBack(true);
        });

        col.addView(title);
        col.addView(status);
        col.addView(setPin);
        col.addView(lockNow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        return scroll;
    }

    private void showMenu() {
        pad = null;
        newPin = null;
        setContentView(menu);
        refresh();
        setPin.requestFocus();
    }

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean pinSet = Pin.isSet(this);
        setPin.setText(pinSet ? "Change PIN" : "Set PIN");

        StringBuilder s = new StringBuilder();
        s.append(overlayOk ? "✓ Overlay permission granted\n"
                : "✗ Overlay permission missing. From your computer run:\n"
                + "   adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
        s.append(pinSet ? "✓ PIN is set" : "✗ No PIN set yet");
        if (overlayOk && pinSet) s.append("\n\nThe TV will lock on startup and every time it wakes.");
        status.setText(s.toString());
    }

    // --- PIN entry flow -------------------------------------------------

    private void startPinFlow() {
        pad = new PinPadView(this);
        pad.setListener(this::onPinEntered);
        setContentView(pad);
        goTo(Pin.isSet(this) ? Step.CURRENT : Step.NEW, null);
    }

    private void goTo(Step s, String message) {
        step = s;
        pad.clear();
        switch (s) {
            case CURRENT: pad.setTitle("Enter current PIN"); break;
            case NEW:     pad.setTitle("Enter new PIN"); break;
            case CONFIRM: pad.setTitle("Confirm new PIN"); break;
        }
        pad.setMessage(message != null ? message
                : s == Step.NEW ? "4–8 digits, then OK.  Back cancels." : "Back cancels.");
    }

    private void onPinEntered(String pin) {
        switch (step) {
            case CURRENT:
                if (Pin.check(this, pin)) goTo(Step.NEW, null);
                else pad.setMessage("Wrong PIN");
                break;
            case NEW:
                if (pin.length() < 4) {
                    pad.setMessage("PIN must be 4–8 digits");
                } else {
                    newPin = pin;
                    goTo(Step.CONFIRM, null);
                }
                break;
            case CONFIRM:
                if (pin.equals(newPin)) {
                    Pin.set(this, pin);
                    Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show();
                    showMenu();
                } else {
                    goTo(Step.NEW, "PINs didn't match. Enter new PIN again.");
                }
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (pad == null) return super.dispatchKeyEvent(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) showMenu();
            return true;
        }
        return pad.handleKey(e) || super.dispatchKeyEvent(e);
    }

    private void startLockService(boolean lockNow) {
        Intent i = new Intent(this, LockService.class);
        i.putExtra(LockService.EXTRA_LOCK_NOW, lockNow);
        startForegroundService(i);
    }
}
