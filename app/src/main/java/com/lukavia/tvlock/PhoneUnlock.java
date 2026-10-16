package com.lukavia.tvlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Unlock from a phone via an ntfy (https://ntfy.sh) push notification.
 *
 * While the lock is on screen the TV publishes a notification to a private
 * topic the phone subscribes to. Its "Unlock" action POSTs a one-time code to
 * a second "-unlock" topic that only the TV listens on; when that code
 * arrives the lock is lifted. A new code is used for every lock session, so
 * old notifications can't unlock the TV.
 */
final class PhoneUnlock {
    static final String DEFAULT_SERVER = "https://ntfy.sh";
    private static final String PREFS = "phone_unlock";
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // --- settings ---------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean enabled) {
        topic(c); // make sure a topic exists before enabling
        prefs(c).edit().putBoolean("enabled", enabled).apply();
    }

    static String server(Context c) {
        return prefs(c).getString("server", DEFAULT_SERVER);
    }

    static void setServer(Context c, String server) {
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        prefs(c).edit().putString("server", s.isEmpty() ? DEFAULT_SERVER : s).apply();
    }

    /** The topic the phone subscribes to; created on first use. */
    static String topic(Context c) {
        String t = prefs(c).getString("topic", null);
        if (t == null) t = newTopic(c);
        return t;
    }

    /** Replaces the topic, so phones subscribed to the old one stop working. */
    static String newTopic(Context c) {
        String t = "tvlock-" + random(20);
        prefs(c).edit().putString("topic", t).apply();
        return t;
    }

    static String subscribeUrl(Context c) {
        return server(c) + "/" + topic(c);
    }

    private static String random(int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return s.toString();
    }

    // --- lock session -----------------------------------------------------

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    PhoneUnlock(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isRunning() {
        return session != null;
    }

    /** Sends the notification and waits for the reply; no-op if disabled or already running. */
    void start(Runnable onUnlock) {
        if (session != null || !isEnabled(ctx)) return;
        session = new Session(server(ctx), topic(ctx), random(24), onUnlock);
        session.thread.start();
    }

    void stop() {
        if (session == null) return;
        session.cancel();
        session = null;
    }

    private final class Session implements Runnable {
        final String server;
        final String topic;
        final String code;
        final Runnable onUnlock;
        final long since = System.currentTimeMillis() / 1000;
        final Thread thread = new Thread(this, "tvlock-phone-unlock");
        volatile boolean cancelled;
        volatile HttpURLConnection conn;

        Session(String server, String topic, String code, Runnable onUnlock) {
            this.server = server;
            this.topic = topic;
            this.code = code;
            this.onUnlock = onUnlock;
        }

        void cancel() {
            cancelled = true;
            thread.interrupt();
            HttpURLConnection c = conn;
            if (c != null) new Thread(c::disconnect).start(); // never touch the network on the main thread
        }

        @Override
        public void run() {
            long backoff = 2_000;
            boolean published = false;
            while (!cancelled) {
                try {
                    // Subscribe first (with "since" so nothing is missed across reconnects),
                    // then publish, so the reply can't arrive before we're listening.
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            server + "/" + topic + "-unlock/json?since=" + since).openConnection();
                    c.setConnectTimeout(15_000);
                    c.setReadTimeout(120_000); // ntfy sends a keepalive every 45s
                    conn = c;
                    if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                    if (!published) {
                        publish();
                        published = true;
                    }
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while (!cancelled && (line = r.readLine()) != null) {
                        JSONObject o = new JSONObject(line);
                        if ("message".equals(o.optString("event"))
                                && code.equals(o.optString("message").trim())) {
                            main.post(() -> {
                                if (!cancelled) onUnlock.run();
                            });
                            return;
                        }
                    }
                    backoff = 2_000;
                } catch (Exception e) {
                    if (cancelled) return;
                } finally {
                    HttpURLConnection c = conn;
                    if (c != null) c.disconnect();
                    conn = null;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            }
        }

        private void publish() throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic).openConnection();
            try {
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Title", "TV Lock");
                c.setRequestProperty("Priority", "high");
                c.setRequestProperty("Tags", "lock");
                c.setRequestProperty("Actions", "http, Unlock, " + server + "/" + topic
                        + "-unlock, method=POST, body=" + code + ", clear=true");
                try (OutputStream out = c.getOutputStream()) {
                    out.write("The TV is locked. Tap Unlock to unlock it."
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            } finally {
                c.disconnect();
            }
        }
    }
}
