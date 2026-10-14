# DeXBridge

An Android TV Miracast / Wi-Fi Display receiver, with a working Samsung Wireless DeX setup documented in this repository.

DeXBridge is independent and is not affiliated with or endorsed by Samsung. Samsung and DeX are trademarks of Samsung Electronics. This project is based on the MIT-licensed [MiracastReceiver project](https://github.com/weekdayjast/MiracastReceiver); the upstream copyright and license notice are retained in [LICENSE](LICENSE).

## Build

Requirements: Android SDK, JDK 17, and the Gradle version configured by the wrapper.

```bash
cd MiracastReceiver
./gradlew -PbuildArm32=true assembleDebug testDebugUnitTest --no-daemon
```

The debug APK is written under `MiracastReceiver/app/build/outputs/apk/`. The `buildArm32` option targets the 32-bit ARM TV used during testing; omit it for the project's default ABI configuration.

## Samsung Wireless DeX notes

**Tested TV:** SHARP AQUOS-TVE21F (Android TV 14 / API 34, 32-bit ARM).

The [S24-to-Sharp TV runbook](S24_TO_SHARP_TV.md) gives the current connection and post-reboot steps. The [Wireless DeX case study](WIRELESS_DEX_CASE_STUDY.md) records the discovery and playback investigation. The successful setup required the TV to advertise a Wi-Fi Display sink, remain available for the phone to form its Wi-Fi Direct group, and allow the player activity to appear over other apps. Privileged WFD configuration may be device-specific; the app build alone does not grant system permissions or root access.

This was validated with one Samsung phone and one Android TV, including two DeX connections separated by a disconnect. Compatibility with other devices is unverified. The TV's shell helper ends on reboot and must be relaunched through Mac ADB.

## License

See [LICENSE](LICENSE). Preserve the upstream MIT copyright and permission notice when redistributing the inherited receiver code.
