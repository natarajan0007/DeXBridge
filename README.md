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

The [Wireless DeX case study](WIRELESS_DEX_CASE_STUDY.md) documents the test setup, discovery and playback fixes, diagnostic steps, and limits. The successful setup required the TV to advertise a Wi-Fi Display sink, remain available for the phone to form its Wi-Fi Direct group, and allow the player activity to appear over other apps. Privileged WFD configuration may be device-specific; the app build alone does not grant system permissions or root access.

This was validated with one Samsung phone and one Android TV. Compatibility with other devices and persistence of the TV's Wi-Fi Direct listener across reboot are not guaranteed.

## License

See [LICENSE](LICENSE). Preserve the upstream MIT copyright and permission notice when redistributing the inherited receiver code.
