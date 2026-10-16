package com.lukavia.tvlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen PIN pad drawn over everything, operated "blind" with the D-pad.
 *
 * The keypad on screen is a static legend and never highlights anything:
 * Up/Down move a hidden row cursor (wrapping), Left/Center/Right press the
 * key in that column of the current row, then the cursor silently returns
 * to the top row. Someone watching the screen only sees the dot count grow.
 * The remote's number keys also work directly.
 */
final class LockOverlay {
    private static final int MAX_DIGITS = 8;
    private static final long LOCKOUT_STEP_MS = 30_000L;
    private static final String BACKSPACE = "⌫";
    private static final String OK = "OK";

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {BACKSPACE, "0", OK},
    };

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder entry = new StringBuilder();

    private View root;
    private TextView dots;
    private TextView msg;

    private int row = 0;
    private int failures = 0;
    private long lockedUntil = 0;

    private final Runnable ticker = this::tick;

    LockOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show() {
        if (root != null) {
            resetEntry();
            return;
        }
        root = buildView();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }
        resetEntry();
        tick();
        root.post(() -> { if (root != null) root.requestFocus(); });
    }

    void hide() {
        handler.removeCallbacks(ticker);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        dots = null;
        msg = null;
        entry.setLength(0);
        row = 0;
    }

    private View buildView() {
        float d = ctx.getResources().getDisplayMetrics().density;

        FrameLayout frame = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                // Consume every key so nothing reaches the app underneath
                // and no view inside the overlay ever shows focus.
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    handleKey(e.getKeyCode());
                }
                return true;
            }
        };
        frame.setFocusable(true);
        frame.setFocusableInTouchMode(true);
        frame.setBackgroundColor(0xFF000000);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text("TV locked", 34, 0xFFFFFFFF);
        dots = text("", 40, 0xFFFFFFFF);
        dots.setMinHeight((int) (56 * d));
        msg = text("", 18, 0xFFBBBBBB);
        msg.setPadding(0, 0, 0, (int) (24 * d));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        int m = (int) (6 * d);
        for (String[] keyRow : KEYS) {
            for (String key : keyRow) {
                TextView k = text(key, 24, 0xFFFFFFFF);
                k.setBackground(rounded(0xFF333333, d));
                GridLayout.LayoutParams p = new GridLayout.LayoutParams();
                p.width = (int) (96 * d);
                p.height = (int) (64 * d);
                p.setMargins(m, m, m, m);
                grid.addView(k, p);
            }
        }

        TextView help = text("▲ ▼  choose row      ◀  ●  ▶  choose key", 16, 0xFF888888);
        help.setPadding(0, (int) (24 * d), 0, 0);

        col.addView(title);
        col.addView(dots);
        col.addView(msg);
        col.addView(grid);
        col.addView(help);
        frame.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return frame;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(false);
        return t;
    }

    private static GradientDrawable rounded(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * d);
        return g;
    }

    private void handleKey(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            onDigit((char) ('0' + (k - KeyEvent.KEYCODE_0)));
            return;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                row = (row + KEYS.length - 1) % KEYS.length;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = (row + 1) % KEYS.length;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                press(0);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press(1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                press(2);
                break;
            default:
                // Back, Home, etc. are swallowed so the lock can't be dismissed.
                break;
        }
    }

    private void press(int column) {
        String key = KEYS[row][column];
        row = 0;
        switch (key) {
            case BACKSPACE:
                if (entry.length() > 0) entry.setLength(entry.length() - 1);
                updateDots();
                break;
            case OK:
                submit();
                break;
            default:
                onDigit(key.charAt(0));
        }
    }

    private void onDigit(char c) {
        row = 0;
        if (isLockedOut() || entry.length() >= MAX_DIGITS) return;
        entry.append(c);
        updateDots();
    }

    private void submit() {
        if (isLockedOut() || entry.length() == 0) return;
        if (Pin.check(ctx, entry.toString())) {
            failures = 0;
            hide();
            return;
        }
        failures++;
        resetEntry();
        if (failures >= 3) {
            // 30s after 3 misses, 60s after 4, 90s after 5, ...
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_STEP_MS * (failures - 2);
            tick();
        } else if (msg != null) {
            msg.setText("Wrong PIN");
        }
    }

    private void resetEntry() {
        entry.setLength(0);
        row = 0;
        updateDots();
        if (msg != null && !isLockedOut()) msg.setText("Enter PIN");
    }

    private boolean isLockedOut() {
        return SystemClock.elapsedRealtime() < lockedUntil;
    }

    private void tick() {
        handler.removeCallbacks(ticker);
        if (msg == null) return;
        long left = lockedUntil - SystemClock.elapsedRealtime();
        if (left <= 0) {
            msg.setText("Enter PIN");
            return;
        }
        msg.setText("Too many attempts. Try again in " + ((left + 999) / 1000) + "s");
        handler.postDelayed(ticker, 500);
    }

    private void updateDots() {
        if (dots == null) return;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) s.append("● ");
        dots.setText(s.toString().trim());
    }
}
