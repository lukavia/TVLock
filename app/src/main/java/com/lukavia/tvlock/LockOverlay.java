package com.lukavia.tvlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Full-screen lock drawn over everything, using the blind {@link PinPadView}. */
final class LockOverlay {
    private static final long LOCKOUT_STEP_MS = 30_000L;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private PinPadView pad;

    private int failures = 0;
    private long lockedUntil = 0;

    private final Runnable ticker = this::tick;

    LockOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show() {
        if (root != null) {
            pad.clear();
            tick();
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
            pad = null;
            return;
        }
        pad.clear();
        tick();
        root.post(() -> { if (root != null) root.requestFocus(); });
    }

    void hide() {
        handler.removeCallbacks(ticker);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        pad = null;
    }

    private FrameLayout buildView() {
        FrameLayout frame = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                // Consume every key: Back etc. are swallowed so the lock can't be dismissed.
                if (pad != null) pad.handleKey(e);
                return true;
            }
        };
        frame.setFocusable(true);
        frame.setFocusableInTouchMode(true);
        frame.setBackgroundColor(0xFF000000);

        pad = new PinPadView(ctx);
        pad.setTitle("TV locked");
        pad.setListener(this::submit);
        frame.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private void submit(String pin) {
        if (isLockedOut()) return;
        if (Pin.check(ctx, pin)) {
            failures = 0;
            hide();
            return;
        }
        failures++;
        if (failures >= 3) {
            // 30s after 3 misses, 60s after 4, 90s after 5, ...
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_STEP_MS * (failures - 2);
            tick();
        } else if (pad != null) {
            pad.setMessage("Wrong PIN");
        }
    }

    private boolean isLockedOut() {
        return SystemClock.elapsedRealtime() < lockedUntil;
    }

    private void tick() {
        handler.removeCallbacks(ticker);
        if (pad == null) return;
        long left = lockedUntil - SystemClock.elapsedRealtime();
        if (left <= 0) {
            pad.setInputEnabled(true);
            pad.setMessage("Enter PIN");
            return;
        }
        pad.setInputEnabled(false);
        pad.setMessage("Too many attempts. Try again in " + ((left + 999) / 1000) + "s");
        handler.postDelayed(ticker, 500);
    }
}
