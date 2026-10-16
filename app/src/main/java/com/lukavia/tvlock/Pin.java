package com.lukavia.tvlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Stores a salted SHA-256 hash of the PIN, never the PIN itself. */
final class Pin {
    private static final String PREFS = "tvlock";

    private Pin() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isSet(Context c) {
        return prefs(c).contains("hash");
    }

    static void set(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(c).edit()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", hash(salt, pin))
                .commit();
    }

    static boolean check(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("salt", null);
        String stored = p.getString("hash", null);
        if (salt == null || stored == null || pin == null) return false;
        String attempt = hash(Base64.decode(salt, Base64.NO_WRAP), pin);
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                attempt.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(pin.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
