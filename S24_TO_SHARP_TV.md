# Cast a Samsung phone to Android TV with Wireless DeX

Verified 14 October 2026: the NexaRemote phone service restored the TV receiver service and Wi-Fi Direct helper after a TV reboot. The TV reappeared in Samsung DeX discovery, and a connection succeeded without a Mac launch. Two earlier connections with a disconnect between them also succeeded.

## Everyday use: TV and phone only

1. Turn on the TV and keep the phone on the same Wi-Fi network. Leave **Keep Wireless DeX available** enabled in NexaRemote. Its notification should say the TV is advertising for Wireless DeX after the TV finishes starting. Recovery can take a few minutes after a reboot.
2. On the phone, open **Settings → Connected devices → Samsung DeX → Connect wirelessly** (or tap **DeX** in Quick settings).
3. Select your TV, tap **Start now**, and accept any DeX connection prompt on the TV or phone.
4. To stop, use **Disconnect display** in Samsung DeX or the DeX Quick settings tile. Start a fresh DeX search to reconnect.

Samsung's phone-side steps are in its [Wireless DeX instructions](https://www.samsung.com/us/support/answer/ANS10010217/). The TV-specific recovery behavior above was checked on this setup.

## Keep discovery available after a TV reboot

Connect to the TV in NexaRemote, then tap **Settings → Keep Wireless DeX available → Enable** and enter the TV's network debugging port. NexaRemote keeps a persistent notification while it maintains discovery. If the TV receiver service does not start at boot, NexaRemote starts it in the background, then starts the shell-level Wi-Fi Direct helper.

The TV may label NexaRemote's one-time network debugging authorization **USB debugging** even though the connection is over Wi-Fi. Approve it with **Always allow from this computer** selected. Repeated requests are not normal: stop **Keep Wireless DeX available** and check the TV's network debugging setting and saved authorization before trying again. Earlier development builds retried with stale requests and changed the phone key, causing multiple prompts; the final build authenticated without another prompt.

Automatic recovery depends on the TV's network debugging endpoint returning after reboot, its address remaining reachable from the phone, and Android allowing NexaRemote's foreground service to run. If the TV address changes, reconnect to it in NexaRemote and update the address in **Keep Wireless DeX available**. Keep NexaRemote installed and allow its persistent notification. If Samsung battery settings stop it, set NexaRemote's battery use to **Unrestricted**.

The receiver app's own auto-start switch was enabled, but it did not start after one reboot. NexaRemote's background start restored it. After a later reboot, the receiver service and helper were running, the TV accepted the Wi-Fi Direct sink advertisement, and the user confirmed a DeX connection.

## If discovery does not recover

- Check NexaRemote's **Wireless DeX availability** notification. **TV ADB is unavailable** means the TV's network debugging endpoint is off, still starting, or no longer at its configured address. The phone cannot restore the helper until that endpoint is reachable.
- If the notification says the TV is advertising but DeX cannot find it, start a fresh DeX search. A full TV reboot restored discovery during earlier testing; a Wi-Fi-only reset did not.
- If the receiver app is stopped, open **DeXBridge Receiver** once on the TV. NexaRemote also tries to restart it automatically.

This procedure applies to the tested phone and TV. An older helper build coincided with a TV watchdog reboot; its cause was not established. The revised helper completed two earlier connections without a reboot, and phone-driven startup plus one connection succeeded after reboot. Longer-running stability remains unverified.
