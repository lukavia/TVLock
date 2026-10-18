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
 * a second "-unlock" topic that the TV listens on; when that code arrives the
 * lock is lifted. "Not now" POSTs a deny for the same code, and any text the
 * parent sends to the main topic is shown on the lock screen. A new code is
 * used for every lock session, so old notifications can't unlock the TV.
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
        if (!s.isEmpty() && !s.contains("://")) s = "https://" + s;
        prefs(c).edit().putString("server", s.isEmpty() ? DEFAULT_SERVER : s).apply();
    }

    /** Optional access token for ntfy servers with access control; "" if none. */
    static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    static void setToken(Context c, String token) {
        prefs(c).edit().putString("token", token.trim()).apply();
    }

    /**
     * Publishes a plain test notification on a background thread and reports
     * "OK" or the error on the main thread.
     */
    static void sendTest(Context c, java.util.function.Consumer<String> result) {
        String server = server(c), token = token(c), topic = topic(c);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String outcome;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(server + "/" + topic).openConnection();
                try {
                    if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(15_000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Title", "TV Lock test");
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write("Test from your TV. Phone unlock can reach the server."
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    checkOk(conn);
                    outcome = "OK";
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                outcome = describe(e);
            }
            String o = outcome;
            main.post(() -> result.accept(o));
        }, "tvlock-ntfy-test").start();
    }

    /** Throws with the HTTP status and ntfy's error text if the response isn't 200. */
    private static void checkOk(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code == 200) return;
        String body = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line != null) body = new JSONObject(line).optString("error", line);
        } catch (Exception ignored) {
        }
        throw new IOException("HTTP " + code + (body.isEmpty() ? "" : " " + body)
                + (code == 401 || code == 403 ? " (check the access token)" : ""));
    }

    static String describe(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "server not found: " + e.getMessage();
        if (e instanceof javax.net.ssl.SSLException) return "HTTPS/certificate error: " + e.getMessage();
        if (e instanceof java.net.MalformedURLException) return "bad server address: " + e.getMessage();
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
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

    /** Callbacks run on the main thread. */
    interface Listener {
        void onUnlock();

        /** "Not now" from the notification, or a free-text message from the phone. */
        void onMessage(String text);

        /** The notification reached the server (also called when a problem clears). */
        void onSent();

        /** Connection trouble while sending or checking for a reply. */
        void onProblem(String problem);
    }

    static final String NOT_NOW = "Not now. Ask again later.";
    private static final long OPEN_TIMEOUT_MS = 10_000;

    private enum StreamResult { UNLOCKED, DROPPED, BUFFERED }
    private static final int MAX_MESSAGE = 200;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    PhoneUnlock(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isRunning() {
        return session != null;
    }

    /** Sends the notification and listens for the reply; no-op if disabled or already running. */
    void start(Listener listener) {
        if (session != null || !isEnabled(ctx)) return;
        session = new Session(server(ctx), token(ctx), topic(ctx), random(24), listener);
        session.thread.start();
    }

    void stop() {
        if (session == null) return;
        session.cancel();
        session = null;
    }

    /**
     * One lock session. Listens on both topics:
     * - {@code <topic>-unlock} receives the notification's action bodies:
     *   the session code unlocks, {@code "deny <code>"} means "Not now".
     * - {@code <topic>} itself, so a message the parent sends to it from the
     *   ntfy app is shown on the lock screen (our own notification is skipped).
     * Messages only ever display text; only the code unlocks.
     */
    private final class Session implements Runnable {
        final String server;
        final String token;
        final String topic;
        final String code;
        final Listener listener;
        final Thread thread = new Thread(this, "tvlock-phone-unlock");
        volatile boolean cancelled;
        volatile HttpURLConnection conn;
        String publishedId;
        String since = Long.toString(System.currentTimeMillis() / 1000);

        Session(String server, String token, String topic, String code, Listener listener) {
            this.server = server;
            this.token = token;
            this.topic = topic;
            this.code = code;
            this.listener = listener;
        }

        void cancel() {
            cancelled = true;
            thread.interrupt();
            HttpURLConnection c = conn;
            if (c != null) new Thread(c::disconnect).start(); // never touch the network on the main thread
        }

        @Override
        public void run() {
            // 1. Send the notification, retrying until it goes through.
            long backoff = 2_000;
            while (!cancelled && publishedId == null) {
                try {
                    publishedId = publish();
                    post(listener::onSent);
                } catch (Exception e) {
                    if (cancelled) return;
                    String problem = describe(e);
                    post(() -> listener.onProblem(problem));
                    if (!sleep(backoff)) return;
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
            // 2. Wait for the reply on a held-open stream: instant, and only one
            //    request per session. "since" makes sure nothing sent after the
            //    notification is missed across reconnects. If a reverse proxy
            //    buffers the stream (ntfy's "open" event never arrives), fall
            //    back to polling slowly enough to stay within ntfy's rate limits.
            long retry = 2_000;
            while (!cancelled) {
                StreamResult result;
                try {
                    result = stream();
                } catch (Exception e) {
                    if (cancelled) return;
                    String problem = "can't check for reply: " + describe(e);
                    post(() -> listener.onProblem(problem));
                    if (!sleep(retry)) return;
                    retry = Math.min(retry * 2, 60_000);
                    continue;
                }
                if (result == StreamResult.UNLOCKED || cancelled) return;
                if (result == StreamResult.BUFFERED) {
                    poll();
                    return;
                }
                // The stream worked and then dropped (e.g. network blip): reconnect.
                retry = 2_000;
                if (!sleep(retry)) return;
            }
        }

        private StreamResult stream() throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic + ","
                    + topic + "-unlock/json?since=" + since).openConnection();
            conn = c;
            boolean[] gotLine = {false};
            boolean[] timedOut = {false};
            // ntfy sends {"event":"open"} right away; if it doesn't show up, the
            // stream is being buffered somewhere, so give up on it quickly.
            Runnable watchdog = () -> {
                if (!gotLine[0]) {
                    timedOut[0] = true;
                    new Thread(c::disconnect).start();
                }
            };
            main.postDelayed(watchdog, OPEN_TIMEOUT_MS);
            try {
                authorize(c);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(120_000); // ntfy sends a keepalive every 45s
                checkOk(c);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (!cancelled && (line = r.readLine()) != null) {
                    if (!gotLine[0]) {
                        gotLine[0] = true;
                        post(listener::onSent); // clears any earlier problem
                    }
                    if (line.trim().isEmpty()) continue;
                    JSONObject o = new JSONObject(line);
                    if (!"message".equals(o.optString("event"))) continue;
                    since = o.optString("id", since);
                    if (handle(o)) return StreamResult.UNLOCKED;
                }
                return StreamResult.DROPPED;
            } catch (IOException e) {
                if (gotLine[0]) return StreamResult.DROPPED;
                if (timedOut[0] && !cancelled) return StreamResult.BUFFERED;
                throw e;
            } finally {
                main.removeCallbacks(watchdog);
                c.disconnect();
                conn = null;
            }
        }

        /** Fallback when streaming doesn't work: poll every 15s (ntfy allows ~1 request / 5s). */
        private void poll() {
            boolean failing = false;
            while (!cancelled) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic + ","
                            + topic + "-unlock/json?poll=1&since=" + since).openConnection();
                    conn = c;
                    try {
                        authorize(c);
                        c.setConnectTimeout(15_000);
                        c.setReadTimeout(15_000);
                        checkOk(c);
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while (!cancelled && (line = r.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            JSONObject o = new JSONObject(line);
                            if (!"message".equals(o.optString("event"))) continue;
                            since = o.optString("id", since);
                            if (handle(o)) return;
                        }
                    } finally {
                        c.disconnect();
                        conn = null;
                    }
                    if (failing) {
                        failing = false;
                        post(listener::onSent);
                    }
                } catch (Exception e) {
                    if (cancelled) return;
                    failing = true;
                    String problem = "can't check for reply: " + describe(e);
                    post(() -> listener.onProblem(problem));
                }
                if (!sleep(15_000)) return;
            }
        }

        private boolean sleep(long ms) {
            try {
                Thread.sleep(ms);
                return !cancelled;
            } catch (InterruptedException e) {
                return false;
            }
        }

        /** Returns true when the session is finished (unlocked). */
        private boolean handle(JSONObject o) {
            String text = o.optString("message").trim();
            if ((topic + "-unlock").equals(o.optString("topic"))) {
                if (code.equals(text)) {
                    post(listener::onUnlock);
                    return true;
                }
                if (("deny " + code).equals(text)) post(() -> listener.onMessage(NOT_NOW));
                return false; // anything else on the unlock topic is ignored
            }
            if (o.optString("id").equals(publishedId) || text.isEmpty()) return false;
            String shown = text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
            post(() -> listener.onMessage(shown));
            return false;
        }

        private void post(Runnable r) {
            main.post(() -> {
                if (!cancelled) r.run();
            });
        }

        private void authorize(HttpURLConnection c) {
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        }

        /** Publishes the notification and returns its message id. */
        private String publish() throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic).openConnection();
            try {
                authorize(c);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                String unlockUrl = server + "/" + topic + "-unlock";
                c.setRequestProperty("Title", "TV Lock");
                c.setRequestProperty("Priority", "high");
                c.setRequestProperty("Tags", "tv");
                // On a server with access control the phone's button taps need the
                // token too, so it is passed along in the action headers.
                String auth = token.isEmpty() ? "" : ", headers.Authorization=Bearer " + token;
                c.setRequestProperty("Actions",
                        "http, Unlock, " + unlockUrl + ", method=POST, body=" + code + ", clear=true" + auth + "; "
                        + "http, Not now, " + unlockUrl + ", method=POST, body=deny " + code + auth);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(("Someone wants to watch TV. Unlock, say Not now, "
                            + "or send a message to this topic to show it on the TV.")
                            .getBytes(StandardCharsets.UTF_8));
                }
                checkOk(c);
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    return new JSONObject(r.readLine()).optString("id");
                }
            } finally {
                c.disconnect();
            }
        }
    }
}
