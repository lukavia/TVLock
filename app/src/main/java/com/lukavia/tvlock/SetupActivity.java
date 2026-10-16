package com.lukavia.tvlock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Launcher screen: check permissions, lock now, set/change the PIN, and
 * configure phone unlock. PINs are entered with the same blind D-pad pad as
 * the lock screen. The phone unlock screen reveals the ntfy topic, so it is
 * only shown after the PIN has been entered.
 *
 * A self-hosted ntfy server can be set from a computer with:
 *   adb shell am start -n com.lukavia.tvlock/.SetupActivity --es ntfy_server https://ntfy.example.com
 */
public class SetupActivity extends Activity {
    private enum Step { CURRENT, NEW, CONFIRM, VERIFY_FOR_PHONE }

    private View menu;
    private TextView status;
    private Button setPin;
    private Button phoneButton;

    private PinPadView pad;
    private Step step;
    private String newPin;
    private boolean onPhoneScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String server = getIntent().getStringExtra("ntfy_server");
        if (server != null) {
            PhoneUnlock.setServer(this, server);
            Toast.makeText(this, "ntfy server set to " + PhoneUnlock.server(this), Toast.LENGTH_LONG).show();
        }
        menu = buildMenu();
        showMenu();
        startLockService(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pad == null && !onPhoneScreen) refresh();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // --- menu -----------------------------------------------------------

    private View buildMenu() {
        int pad = dp(48);

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
        setPin.setOnClickListener(v -> startPinFlow(Pin.isSet(this) ? Step.CURRENT : Step.NEW));

        phoneButton = new Button(this);
        phoneButton.setText("Phone unlock");
        phoneButton.setOnClickListener(v -> {
            if (!Pin.isSet(this)) {
                Toast.makeText(this, "Set a PIN first", Toast.LENGTH_SHORT).show();
            } else {
                startPinFlow(Step.VERIFY_FOR_PHONE);
            }
        });

        Button lockNow = new Button(this);
        lockNow.setText("Lock now");
        lockNow.setOnClickListener(v -> {
            startLockService(true);
            moveTaskToBack(true);
        });

        col.addView(title);
        col.addView(status);
        col.addView(setPin);
        col.addView(phoneButton);
        col.addView(lockNow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        return scroll;
    }

    private void showMenu() {
        pad = null;
        newPin = null;
        onPhoneScreen = false;
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
        s.append(pinSet ? "✓ PIN is set\n" : "✗ No PIN set yet\n");
        s.append(PhoneUnlock.isEnabled(this) ? "✓ Phone unlock is on" : "– Phone unlock is off");
        if (overlayOk && pinSet) s.append("\n\nThe TV will lock on startup and every time it wakes.");
        status.setText(s.toString());
    }

    // --- PIN entry flow -------------------------------------------------

    private void startPinFlow(Step first) {
        onPhoneScreen = false;
        pad = new PinPadView(this);
        pad.setListener(this::onPinEntered);
        setContentView(pad);
        goTo(first, null);
    }

    private void goTo(Step s, String message) {
        step = s;
        pad.clear();
        switch (s) {
            case CURRENT:          pad.setTitle("Enter current PIN"); break;
            case NEW:              pad.setTitle("Enter new PIN"); break;
            case CONFIRM:          pad.setTitle("Confirm new PIN"); break;
            case VERIFY_FOR_PHONE: pad.setTitle("Enter PIN"); break;
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
            case VERIFY_FOR_PHONE:
                if (Pin.check(this, pin)) showPhoneScreen();
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

    // --- phone unlock screen --------------------------------------------

    private void showPhoneScreen() {
        pad = null;
        onPhoneScreen = true;
        boolean enabled = PhoneUnlock.isEnabled(this);
        int gap = dp(32);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(gap, gap, gap, gap);

        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(PhoneUnlock.subscribeUrl(this), dp(240)));
        qr.setFocusable(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(gap, 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("Phone unlock: " + (enabled ? "on" : "off"));
        title.setTextSize(28);

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, dp(12), 0, dp(12));
        info.setText("1. Install the free ntfy app on your phone (Android or iPhone).\n"
                + "2. Scan the QR code, or in the app add a subscription with\n"
                + "     Server: " + PhoneUnlock.server(this) + "\n"
                + "     Topic:  " + PhoneUnlock.topic(this) + "\n"
                + "3. When the TV locks, your phone gets a notification.\n"
                + "    Tap Unlock on it to unlock the TV.\n\n"
                + "Anyone who knows the topic can unlock the TV. If it leaks,\n"
                + "press New topic and subscribe again.");

        Button toggle = new Button(this);
        toggle.setText(enabled ? "Turn off" : "Turn on");
        toggle.setOnClickListener(v -> {
            PhoneUnlock.setEnabled(this, !enabled);
            showPhoneScreen();
        });

        Button newTopic = new Button(this);
        newTopic.setText("New topic");
        newTopic.setOnClickListener(v -> {
            PhoneUnlock.newTopic(this);
            Toast.makeText(this, "New topic created. Subscribe to it again on your phone.",
                    Toast.LENGTH_LONG).show();
            showPhoneScreen();
        });

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> showMenu());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(toggle);
        buttons.addView(newTopic);
        buttons.addView(back);

        col.addView(title);
        col.addView(info);
        col.addView(buttons);
        row.addView(qr);
        row.addView(col);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(row);
        setContentView(scroll);
        toggle.requestFocus();
    }

    private static Bitmap qrBitmap(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, size, 0, 0, size, size);
            return b;
        } catch (WriterException e) {
            return null;
        }
    }

    // --- keys -------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if ((pad != null || onPhoneScreen) && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) showMenu();
            return true;
        }
        if (pad != null) return pad.handleKey(e) || super.dispatchKeyEvent(e);
        return super.dispatchKeyEvent(e);
    }

    private void startLockService(boolean lockNow) {
        Intent i = new Intent(this, LockService.class);
        i.putExtra(LockService.EXTRA_LOCK_NOW, lockNow);
        startForegroundService(i);
    }
}
