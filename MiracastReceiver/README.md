# Android TV receiver

This directory contains the buildable Android application used by DeXBridge. The app includes AirPlay, DLNA, and Miracast / Wi-Fi Display receiver components.

From this directory, build and run unit tests with:

```bash
./gradlew -PbuildArm32=true assembleDebug testDebugUnitTest --no-daemon
```

See the repository [README](../README.md) and [Wireless DeX case study](../WIRELESS_DEX_CASE_STUDY.md) for project context, setup, and limitations. The inherited receiver is distributed under the MIT terms in the repository's [LICENSE](../LICENSE).
