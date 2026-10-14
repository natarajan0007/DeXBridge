# Connect the Galaxy S24 Ultra to Family Room TV

Current setup, verified 14 October 2026: Samsung Galaxy S24 Ultra → SHARP AQUOS-TVE21F with Wireless DeX. The TV has the DeXBridge receiver app installed and a shell-UID WFD helper deployed. Two DeX connections with a disconnect between them succeeded using the helper source in commit c0632b2.

## Connect when the TV has not rebooted

1. Turn on the TV and open the installed receiver app if it is not already running. The WFD helper must also be running. The TV should appear to the phone as **Family Room TV**.
2. On the S24, open **Settings → Connected devices → Samsung DeX → Connect wirelessly**. You can also open the Quick settings panel and tap **DeX**.
3. Select **Family Room TV**, then tap **Start now**. Accept any connection prompt on the TV and any permission prompt on the phone.
4. To finish, open **Settings → Connected devices → Samsung DeX → Disconnect display**, or tap the DeX Quick settings tile. Start a fresh search to connect again. The helper restarts the TV's Wi-Fi Direct listener after a disconnect.

The phone menu and confirmation steps follow [Samsung's Wireless DeX instructions](https://www.samsung.com/us/support/answer/ANS10010217/). The TV-specific result above was verified on this setup.

## After the TV reboots

The TV reboot ends the receiver process and the shell helper. Git stores the helper source, but it does not start the helper on the TV. Use a Mac with an authorized ADB connection to relaunch both before searching from the S24.

The TV's tested ADB address was `192.168.1.113:5555`; use its current address if DHCP changes it. The helper dex below is already on this TV at `/data/local/tmp/WfdProbe-recovery-v2-20261014.dex`.

```sh
TV_ADB=192.168.1.113:5555
adb connect "$TV_ADB"
adb -s "$TV_ADB" shell 'ls -l /data/local/tmp/WfdProbe-recovery-v2-20261014.dex'
adb -s "$TV_ADB" shell "ps -A -o PID,ARGS | grep '[W]fdProbe 0'"
```

If the final command shows an `app_process ... WfdProbe 0` process, the helper is already running; do not launch a second one. Otherwise run:

```sh
adb -s "$TV_ADB" shell "rm -f /data/local/tmp/wfdprobe.stop; setsid sh -c 'exec app_process -Djava.class.path=/data/local/tmp/WfdProbe-recovery-v2-20261014.dex /data/local/tmp WfdProbe 0 > /data/local/tmp/wfdprobe-recovery-v2-20261014.log 2>&1 </dev/null' >/dev/null 2>&1 &"
adb -s "$TV_ADB" shell 'am start -n com.weekd.miracastreceiver/.ui.MainActivity'
adb -s "$TV_ADB" shell 'input keyevent KEYCODE_HOME'
adb -s "$TV_ADB" shell 'tail -n 15 /data/local/tmp/wfdprobe-recovery-v2-20261014.log'
adb -s "$TV_ADB" shell "dumpsys wifip2p | grep '^mListenStarted'"
```

Look for `WFD_SINK_ADVERTISEMENT_ACCEPTED`, `P2P_LISTEN_STARTED`, and `mListenStarted true`. Then follow the S24 connection steps above. These checks confirm the TV's local state; the phone finding **Family Room TV** confirms discovery.

If the helper dex is missing, run the following from this repository's root with JDK 17 and Android SDK platform 37.0/build-tools 36.0.0 installed. These are the tool versions used for the current deployment. Then run the launch block above.

```sh
WFD_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
WFD_BUILD="$(mktemp -d)"
mkdir -p "$WFD_BUILD/classes" "$WFD_BUILD/dex"
javac -source 17 -target 17 -cp "$WFD_SDK/platforms/android-37.0/android.jar" -d "$WFD_BUILD/classes" tools/adb-wfd/WfdProbe.java
"$WFD_SDK/build-tools/36.0.0/d8" --lib "$WFD_SDK/platforms/android-37.0/android.jar" --min-api 34 --output "$WFD_BUILD/dex" "$WFD_BUILD/classes/"*.class
adb -s "$TV_ADB" push "$WFD_BUILD/dex/classes.dex" /data/local/tmp/WfdProbe-recovery-v2-20261014.dex
```

The dex is a generated device artifact and is not stored in Git.

## If the TV disappears from DeX discovery

- Check that the helper process and receiver app are running. Inspect `/data/local/tmp/wfdprobe-recovery-v2-20261014.log` on the TV.
- A TV Wi-Fi-only reset did not restore discovery during testing. A full TV reboot did; after reboot, repeat the Mac/ADB steps above.
- One earlier connection attempt caused a TV watchdog reboot with an older recovery-helper build. Its cause was not established. The revised helper completed two connections without a reboot; longer-term stability is still unverified.

This procedure is specific to the tested S24 Ultra and Sharp TV. It assumes the receiver app is installed, the TV has granted it display-over-other-apps permission, and Mac ADB access is already authorized.
