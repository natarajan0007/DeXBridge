# Getting Samsung Wireless DeX working with an Android TV receiver

This is a field report from getting Samsung Wireless DeX to discover and play on an Android TV. The receiver is based on the MIT-licensed [MiracastReceiver project](https://github.com/weekdayjast/MiracastReceiver); it is not an entirely original implementation. Keep the upstream license and copyright notice with any fork or redistributed copy.

## What I built

I used an Android TV as a Miracast / Wi-Fi Display sink and a Samsung Galaxy S24 Ultra as the source. The receiver already handled the Miracast session and video transport; the hard part was making discovery, connection setup, and Android's playback UI all work together on this particular TV.

The receiver is an Android/Kotlin app. Its Wi-Fi Direct manager advertises the TV as a display sink, its WFD server handles the RTSP connection, and the player renders the incoming stream. The project also supports other casting protocols; this report focuses only on Wireless DeX.

## What I learned

- “Not in the DeX list” does not necessarily mean an app problem or low storage. Wi-Fi Direct discovery has its own device name, WFD advertisement, and listen state, separate from normal Wi-Fi and the TV's Android device name.
- Seeing a TV name is only the first stage. A successful connection still has to form a Wi-Fi Direct group, get an IP address, negotiate RTSP, receive RTP video, and launch a visible player.
- A TV can receive the video packets while showing no picture. On Android 14, the system blocked the receiver activity from launching over the home screen while it was in the background. The TV's connection prompt had been accepted, but the phone eventually timed out after the playback screen failed to appear.
- Wi-Fi Direct names such as `Android_xxxx` are not the same as the TV's regular device name. A privileged system call changed the Wi-Fi Direct label in this test. Ordinary apps may not have permission to do that, and persistence across a TV reboot was not verified.
- Logs helped locate the failing stage. They showed that P2P negotiation, DHCP, RTSP `SETUP`/`PLAY`, and RTP reception were succeeding; the remaining failure was the Android background-activity restriction, not a lack of incoming stream data.

## Changes and debugging that got this setup working

The receiver source in this working copy was adjusted to:

1. Leave Wi-Fi Direct available for the Samsung phone to initiate the group, instead of having the TV create a group or start peer service discovery at receiver startup.
2. Set the WFD sink information using the Android method available on this TV, and reapply the sink advertisement when the receiver service starts where the device permits it.
3. Find the TV's Wi-Fi Direct network and bind source-connection probes to that network. The host scan includes the usual group-owner address while skipping the TV's own address.
4. Explain and request Android's “display over other apps” permission, which is needed on this setup for the player screen to appear when a DeX session starts in the background.

For the successful test, I also used a temporary ADB shell-UID diagnostic helper to restore the TV's truthful sink advertisement and keep Wi-Fi Direct listening. This made the TV show up in the phone's peer list again. The helper is a troubleshooting aid, not a production service: its listener state is temporary and has not been shown to survive a TV reboot. The successful session was confirmed after granting the overlay permission and refreshing discovery.

The stable Wi-Fi Direct label helped identify the TV, but it was not itself the discovery fix. The important pieces were the active sink advertisement/listener, letting the phone form the P2P group, and allowing Android to display the receiver player.

## Build the receiver

The upstream project documents Android Studio, Android SDK, JDK, and Gradle requirements in its README. From the receiver's Android project directory, a 32-bit debug build can be made with:

```bash
cd MiracastReceiver
./gradlew -PbuildArm32=true assembleDebug --no-daemon
```

The resulting APK is under `app/build/outputs/apk/`. Choose the ABI that matches the TV. Building an APK does not grant privileged Wi-Fi Direct access or the Android overlay permission; device and OS capabilities still matter.

## Limits of this result

This confirms one end-to-end session on one Samsung phone and one Android TV. It does not establish compatibility with every TV, a listener that survives reboot, automatic Wi-Fi Direct name persistence, or a root-free production setup. Some source changes in this working copy still need a clean-install and reboot validation before they should be described as generally supported.

The helper's compiled `.class` and `.dex` outputs are local build artifacts and should not be committed. Before publishing a fork, review the complete diff, remove machine-specific configuration and generated files, retain the upstream MIT notice, and publish to a new repository or an explicitly intended fork—not accidentally to the upstream project's remote.
