# TV Lock for Chromecast with Google TV

Shows a full-screen PIN pad when the Chromecast boots and every time it wakes
from standby. The PIN is entered "blind" with the remote's D-pad, so nobody
watching the screen can tell which digits you press. Back is blocked;
three wrong PINs trigger a 30-second lockout that grows with each extra miss.

## Entering the PIN
The current row is highlighted, but never the individual key you press:
```
 1  2  3
 4  5  6
 7  8  9
 ⌫  0  OK
```
- The cursor starts on the **top row** (`1 2 3`).
- **Up / Down** move it to another row (it wraps, so one **Up** reaches `⌫ 0 OK`).
- **Left / Center / Right** press the left, middle or right key of that row.
  The cursor stays on that row until you move it.

Example, starting on the top row: `1590` = Left · Down, Center ·
Down, Right · Down, Center · then OK = Right.

Someone watching sees which row you're on, but not which of its three keys
you pressed. A remote with number keys can
also type digits directly.

## 1. Build the APK
**From GitHub (no local setup):** every push runs the *Build APK* workflow.
Open the run under the **Actions** tab and download the `tvlock-apk`
artifact (`tvlock.apk` inside). Pushing a tag like `v1.0` also attaches the APK to a GitHub Release.

**Locally:** with JDK 17 and the Android SDK installed (or Android Studio):
```
./gradlew assembleDebug
```
The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Signing key
CI signs the APK with a fixed key so updates install over the old version
(`adb install -r`) and keep your PIN. The key lives only in two repository
secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
| --- | --- |
| `TVLOCK_KEYSTORE_BASE64` | the PKCS12 keystore, base64-encoded (alias `tvlock`) |
| `TVLOCK_KEYSTORE_PASSWORD` | its password |

Without them CI falls back to a debug build signed with a throwaway key.
Keep a backup of the keystore: if it's lost, the next install needs an
uninstall first, which wipes the PIN.

## 2. Enable ADB on the Chromecast
1. Settings > System > About > click **Android TV OS build** 7 times.
2. Settings > System > Developer options > turn on **USB debugging**.
3. Find the Chromecast's IP (Settings > Network & Internet > your network).
4. On your computer: `adb connect <chromecast-ip>:5555`, then approve the
   prompt on the TV. Tick **Always allow from this computer**.

## 3. Install and grant permission
```
adb install -r tvlock.apk
adb shell appops set com.lukavia.tvlock SYSTEM_ALERT_WINDOW allow
```
Google TV hides the "Display over other apps" screen, so the `appops` command
is the only way to grant the overlay permission.

> Upgrading from a build with the old `com.example.tvlock` package? It is a
> different app to Android, so remove it first: `adb uninstall com.example.tvlock`

## 4. Set your PIN
Open **TV Lock** from Apps on the Chromecast and choose **Set PIN** (or
**Change PIN**). PINs are entered with the same hidden D-pad keypad as the
lock screen (see *Entering the PIN* above): current PIN if one is set, then
the new PIN, then the new PIN again. Back cancels. Then press **Lock now**
to test. Also test by turning the TV off and on.

## 5. Unlock from your phone (optional)
Instead of typing the PIN you can unlock with one tap on your phone, using
the free [ntfy](https://ntfy.sh) push app (Android and iPhone).

1. Install **ntfy** on your phone.
2. On the TV open **TV Lock > Phone unlock** (asks for the PIN) and press
   **Turn on**.
3. Scan the QR code, or add a subscription in the ntfy app using the server
   and topic shown on screen.

Whenever the lock screen appears while the TV is on, your phone gets a
"TV Lock" notification with two buttons:
- **Unlock** unlocks the TV.
- **Not now** keeps it locked and shows "Not now. Ask again later." on the
  TV. The notification stays, so you can still tap **Unlock** later.

To send your own message instead (e.g. "No TV until homework is done"),
open the TV Lock topic in the ntfy app and send a message to it; the TV
shows it on the lock screen. Messages never unlock the TV.

The PIN keeps working as before, and nothing is sent while the TV is in
standby.

How it works and what to know:
- The TV publishes to your private topic and listens on `<topic>-unlock`
  for a one-time code that changes every time it locks, so an old
  notification can't unlock it later. It also listens on the topic itself
  for text messages to display.
- Anyone who knows the topic name can unlock the TV. It's random and only
  shown after entering the PIN; if it leaks, press **New topic** and
  subscribe again.
- It needs internet access and uses the public ntfy.sh server by default.

### Using your own ntfy server
On **Phone unlock** press **Server** and enter its address, e.g.
`https://ntfy.example.com` (HTTPS is required; Android blocks plain HTTP).
Then subscribe to the topic on your phone using the same server.

If the server has access control (`auth-default-access: deny-all`), create a
token and press **Access token** to enter it:
```
ntfy user add tvlock
ntfy access tvlock 'tvlock-*' rw
ntfy token add tvlock
```
Log in to the server in the ntfy app on your phone too. The token is also
included in the notification's Unlock / Not now buttons (they need it to
post back), so give it access to the `tvlock-*` topics only, as above.

For iPhone notifications from a self-hosted server, the server needs
`upstream-base-url: "https://ntfy.sh"` in its config.

## Forgot the PIN?
From your already-authorised computer:
```
adb shell pm clear com.lukavia.tvlock    # wipes the PIN
# or
adb uninstall com.lukavia.tvlock
```
Leave USB debugging on so this recovery path stays available. Without it,
the only fallback is a factory reset.

## Limitations
- It's a deterrent, not a security boundary.
- For a few seconds after a cold boot, before Android delivers BOOT_COMPLETED,
  the home screen is usable.
- Anyone with ADB access to your network and an authorised computer can remove it.
- Changing the PIN requires the current PIN.
- Phone unlock is only as private as its ntfy topic name.
