package com.example.tvlock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen PIN pad drawn over everything, navigable with the D-pad remote. */
final class LockOverlay {
    private static final int MAX_DIGITS = 8;
    private static final long LOCKOUT_STEP_MS = 30_000L;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder entry = new StringBuilder();

    private View root;
    private TextView dots;
    private TextView msg;
    private Button firstKey;

    private int failures = 0;
    private long lockedUntil = 0;

    private final Runnable ticker = this::tick;

    LockOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show() {
        if (root != null) {
            entry.setLength(0);
            updateDots();
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
        entry.setLength(0);
        updateDots();
        tick();
        root.post(() -> { if (firstKey != null) firstKey.requestFocus(); });
    }

    void hide() {
        handler.removeCallbacks(ticker);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        dots = null;
        msg = null;
        firstKey = null;
        entry.setLength(0);
    }

    private View buildView() {
        float d = ctx.getResources().getDisplayMetrics().density;

        FrameLayout frame = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                    return true; // swallow Back so the lock can't be dismissed
                }
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        onDigit((char) ('0' + (k - KeyEvent.KEYCODE_0)));
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        frame.setBackgroundColor(0xFF000000);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text("TV locked", 34, 0xFFFFFFFF);
        dots = text("", 40, 0xFFFFFFFF);
        dots.setMinHeight((int) (56 * d));
        msg = text("Enter PIN", 18, 0xFFBBBBBB);
        msg.setPadding(0, 0, 0, (int) (24 * d));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        int m = (int) (6 * d);
        for (String key : keys) {
            Button b = makeKey(key, d);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = (int) (96 * d);
            p.height = (int) (64 * d);
            p.setMargins(m, m, m, m);
            grid.addView(b, p);
            if (firstKey == null) firstKey = b;
        }

        col.addView(title);
        col.addView(dots);
        col.addView(msg);
        col.addView(grid);
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
        return t;
    }

    private Button makeKey(String label, float d) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextSize(24);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setFocusable(true);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(0xFFFFFFFF, d));
        bg.addState(new int[]{}, rounded(0xFF333333, d));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF000000, 0xFFFFFFFF}));

        b.setOnClickListener(v -> onKey(label));
        return b;
    }

    private static GradientDrawable rounded(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * d);
        return g;
    }

    private void onKey(String label) {
        switch (label) {
            case "⌫":
                if (entry.length() > 0) entry.setLength(entry.length() - 1);
                updateDots();
                break;
            case "OK":
                submit();
                break;
            default:
                onDigit(label.charAt(0));
        }
    }

    private void onDigit(char c) {
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
        entry.setLength(0);
        updateDots();
        if (failures >= 3) {
            // 30s after 3 misses, 60s after 4, 90s after 5, ...
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_STEP_MS * (failures - 2);
            tick();
        } else if (msg != null) {
            msg.setText("Wrong PIN");
        }
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
