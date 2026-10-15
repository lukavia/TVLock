package com.example.tvlock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Launcher screen: set or change the PIN, check permissions, lock now. */
public class SetupActivity extends Activity {
    private TextView status;
    private EditText current;
    private EditText pin1;
    private EditText pin2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        current = pinField("Current PIN");
        pin1 = pinField("New PIN (4–8 digits)");
        pin2 = pinField("Confirm new PIN");

        Button save = new Button(this);
        save.setText("Save PIN");
        save.setOnClickListener(v -> savePin());

        Button lockNow = new Button(this);
        lockNow.setText("Lock now");
        lockNow.setOnClickListener(v -> {
            startLockService(true);
            moveTaskToBack(true);
        });

        col.addView(title);
        col.addView(status);
        col.addView(current);
        col.addView(pin1);
        col.addView(pin2);
        col.addView(save);
        col.addView(lockNow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);

        startLockService(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setEms(12);
        return e;
    }

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean pinSet = Pin.isSet(this);
        current.setVisibility(pinSet ? View.VISIBLE : View.GONE);

        StringBuilder s = new StringBuilder();
        s.append(overlayOk ? "✓ Overlay permission granted\n"
                : "✗ Overlay permission missing. From your computer run:\n"
                + "   adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
        s.append(pinSet ? "✓ PIN is set" : "✗ No PIN set yet");
        if (overlayOk && pinSet) s.append("\n\nThe TV will lock on startup and every time it wakes.");
        status.setText(s.toString());
    }

    private void savePin() {
        if (Pin.isSet(this) && !Pin.check(this, current.getText().toString())) {
            toast("Current PIN is wrong");
            return;
        }
        String a = pin1.getText().toString();
        String b = pin2.getText().toString();
        if (!a.matches("\\d{4,8}")) {
            toast("PIN must be 4–8 digits");
            return;
        }
        if (!a.equals(b)) {
            toast("PINs don't match");
            return;
        }
        Pin.set(this, a);
        current.setText("");
        pin1.setText("");
        pin2.setText("");
        toast("PIN saved");
        refresh();
    }

    private void startLockService(boolean lockNow) {
        Intent i = new Intent(this, LockService.class);
        i.putExtra(LockService.EXTRA_LOCK_NOW, lockNow);
        startForegroundService(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
