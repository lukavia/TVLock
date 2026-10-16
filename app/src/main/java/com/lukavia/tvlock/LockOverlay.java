package com.lukavia.tvlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Full-screen lock drawn over everything, using the blind {@link PinPadView}.
 * While the screen is on it also offers {@link PhoneUnlock} if enabled.
 */
final class LockOverlay implements PhoneUnlock.Listener {
    private static final long LOCKOUT_STEP_MS = 30_000L;

    private final Context ctx;
    private final WindowManager wm;
    private final PowerManager pm;
    private final PhoneUnlock phone;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private PinPadView pad;

    private int failures = 0;
    private long lockedUntil = 0;
    /** Latest reply from the phone ("Not now" or a custom message), shown under the title. */
    private String phoneNote;
    /** Why the phone notification couldn't be sent yet, if anything. */
    private String phoneProblem;

    private final Runnable ticker = this::tick;

    LockOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.pm = ctx.getSystemService(PowerManager.class);
        this.phone = new PhoneUnlock(ctx);
    }

    void show() {
        if (root != null) {
            pad.clear();
            updatePhone();
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
        updatePhone();
        root.post(() -> { if (root != null) root.requestFocus(); });
    }

    void hide() {
        phone.stop();
        phoneNote = null;
        phoneProblem = null;
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

    /**
     * Only notify the phone while the screen is on: the lock is also put up
     * when the TV goes to standby, and that shouldn't ping anyone.
     */
    private void updatePhone() {
        if (root != null && pm.isInteractive()) {
            phone.start(this);
        } else {
            phone.stop();
            phoneNote = null; // a new request goes out next time the screen turns on
            phoneProblem = null;
        }
        tick();
    }

    @Override
    public void onUnlock() {
        unlock();
    }

    @Override
    public void onMessage(String text) {
        phoneNote = text;
        tick();
    }

    @Override
    public void onProblem(String problemOrNull) {
        phoneProblem = problemOrNull;
        tick();
    }

    private void unlock() {
        failures = 0;
        lockedUntil = 0;
        hide();
    }

    private void submit(String pin) {
        if (isLockedOut()) return;
        if (Pin.check(ctx, pin)) {
            unlock();
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
            if (phoneNote != null) {
                pad.setMessage("From phone: " + phoneNote);
            } else if (phoneProblem != null) {
                pad.setMessage("Enter PIN.  (Phone unlock: " + phoneProblem + ")");
            } else {
                pad.setMessage(phone.isRunning() ? "Enter PIN, or tap Unlock on your phone" : "Enter PIN");
            }
            return;
        }
        pad.setInputEnabled(false);
        pad.setMessage("Too many attempts. Try again in " + ((left + 999) / 1000) + "s");
        handler.postDelayed(ticker, 500);
    }
}
