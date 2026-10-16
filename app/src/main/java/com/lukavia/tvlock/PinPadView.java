package com.lukavia.tvlock;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PIN pad operated "blind" with the D-pad.
 *
 * Up/Down move a row cursor (wrapping); the whole current row is highlighted.
 * Left/Center/Right press the key in that column of the current row, and the
 * cursor returns to the top row. No individual key is ever highlighted, so
 * someone watching the screen sees the row but not which of its keys you chose.
 * The remote's number keys also work directly.
 *
 * The view never takes focus; the hosting window forwards key events to
 * {@link #handleKey(KeyEvent)}.
 */
final class PinPadView extends LinearLayout {
    interface Listener {
        void onSubmit(String pin);
    }

    static final int MAX_DIGITS = 8;
    private static final String BACKSPACE = "⌫";
    private static final String OK = "OK";

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {BACKSPACE, "0", OK},
    };

    private final StringBuilder entry = new StringBuilder();
    private final TextView title;
    private final TextView dots;
    private final TextView msg;
    private final TextView[][] keyViews = new TextView[KEYS.length][];
    private final float density;

    private Listener listener;
    private boolean inputEnabled = true;
    private int row = 0;

    PinPadView(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        density = d;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xFF000000);
        setFocusable(false);

        title = text("", 34, 0xFFFFFFFF);
        dots = text("", 40, 0xFFFFFFFF);
        dots.setMinHeight((int) (56 * d));
        msg = text("", 18, 0xFFBBBBBB);
        msg.setPadding(0, 0, 0, (int) (24 * d));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        int m = (int) (6 * d);
        for (int r = 0; r < KEYS.length; r++) {
            keyViews[r] = new TextView[KEYS[r].length];
            for (int c = 0; c < KEYS[r].length; c++) {
                TextView k = text(KEYS[r][c], 24, 0xFFFFFFFF);
                keyViews[r][c] = k;
                GridLayout.LayoutParams p = new GridLayout.LayoutParams();
                p.width = (int) (96 * d);
                p.height = (int) (64 * d);
                p.setMargins(m, m, m, m);
                grid.addView(k, p);
            }
        }

        TextView help = text("▲ ▼  choose row      ◀  ●  ▶  choose key", 16, 0xFF888888);
        help.setPadding(0, (int) (24 * d), 0, 0);

        addView(title, wrap());
        addView(dots, wrap());
        addView(msg, wrap());
        addView(grid, wrap());
        addView(help, wrap());
        updateRow();
    }

    void setListener(Listener l) { listener = l; }
    void setTitle(CharSequence s) { title.setText(s); }
    void setMessage(CharSequence s) { msg.setText(s); }
    void setInputEnabled(boolean enabled) { inputEnabled = enabled; }

    /** Clears the entered digits and returns the hidden cursor to the top row. */
    void clear() {
        entry.setLength(0);
        row = 0;
        updateDots();
        updateRow();
    }

    /** Returns true if the key belongs to the pad (and was consumed). */
    boolean handleKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (!isPadKey(k)) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;

        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            row = 0;
            updateRow();
            digit((char) ('0' + (k - KeyEvent.KEYCODE_0)));
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                row = (row + KEYS.length - 1) % KEYS.length;
                updateRow();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = (row + 1) % KEYS.length;
                updateRow();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                press(0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                press(2);
                break;
            default: // center / enter
                press(1);
                break;
        }
        return true;
    }

    private static boolean isPadKey(int k) {
        return (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9)
                || k == KeyEvent.KEYCODE_DPAD_UP
                || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void press(int column) {
        String key = KEYS[row][column];
        row = 0;
        updateRow();
        switch (key) {
            case BACKSPACE:
                if (inputEnabled && entry.length() > 0) entry.setLength(entry.length() - 1);
                updateDots();
                break;
            case OK:
                if (inputEnabled && entry.length() > 0 && listener != null) {
                    String pin = entry.toString();
                    clear();
                    listener.onSubmit(pin);
                }
                break;
            default:
                digit(key.charAt(0));
        }
    }

    private void digit(char c) {
        if (!inputEnabled || entry.length() >= MAX_DIGITS) return;
        entry.append(c);
        updateDots();
    }

    private void updateRow() {
        for (int r = 0; r < keyViews.length; r++) {
            for (TextView k : keyViews[r]) k.setBackground(keyBackground(r == row));
        }
    }

    private void updateDots() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) s.append("● ");
        dots.setText(s.toString().trim());
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(false);
        return t;
    }

    private static LayoutParams wrap() {
        LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private GradientDrawable keyBackground(boolean currentRow) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(currentRow ? 0xFF5A5A5A : 0xFF333333);
        g.setCornerRadius(12 * density);
        if (currentRow) g.setStroke((int) (2 * density), 0xFFFFFFFF);
        return g;
    }
}
