import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Method;

/** Shell-UID WFD discovery helper. No root, firmware edits, or group creation. */
public final class WfdProbe {
    private static WifiP2pManager manager;
    private static WifiP2pManager.Channel channel;
    private static Handler handler;
    private static Object original;
    private static Object sinkInfo;
    private static Method setInfo;
    private static boolean changed;
    private static boolean finishing;
    private static boolean persistent;
    private static final File STOP_FILE = new File("/data/local/tmp/wfdprobe.stop");

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE_START uid=" + android.os.Process.myUid());
        Looper.prepareMainLooper();
        handler = new Handler(Looper.getMainLooper());
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        System.out.println("SYSTEM_CONTEXT_READY");
        Context context = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(android.os.Process.myUid())
                        .setPackageName("com.android.shell").build();
            }
        };
        System.out.println("WRAPPER_READY");
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        System.out.println("MANAGER_READY");
        if (manager == null) throw new IllegalStateException("No Wi-Fi P2P service");
        System.out.println("SHELL_CONTEXT_READY");
        channel = manager.initialize(context, Looper.getMainLooper(), () -> finish("Channel disconnected"));
        System.out.println("P2P_CHANNEL_READY");
        Class<?> infoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo");
        setInfo = WifiP2pManager.class.getMethod("setWFDInfo", WifiP2pManager.Channel.class,
                infoClass, WifiP2pManager.ActionListener.class);
        if (args.length > 0 && "local".equals(args[0])) {
            manager.requestDeviceInfo(channel, device -> {
                System.out.println("LOCAL " + device + " wfd=" + device.getWfdInfo());
                System.exit(0);
            });
            Looper.loop();
            return;
        }
        if (args.length > 0 && "peers".equals(args[0])) {
            System.out.println("PEER_MONITOR_START");
            handler.post(WfdProbe::dumpPeers);
            Looper.loop();
            return;
        }
        int requestedSeconds = args.length == 0 ? 120 : Integer.parseInt(args[0]);
        persistent = requestedSeconds == 0;
        if (persistent) {
            STOP_FILE.delete();
            System.out.println("PERSISTENT_MODE: create " + STOP_FILE + " to stop and restore original WFD state.");
            handler.postDelayed(WfdProbe::checkStopFile, 1000L);
        } else {
            int seconds = Math.max(15, Math.min(300, requestedSeconds));
            handler.postDelayed(() -> finish("Probe timeout"), seconds * 1000L);
        }
        if (persistent) {
            // WFD information alone does not keep P2P discoverable. Maintain
            // listen state for the Samsung source's peer search.
            handler.postDelayed(() -> advertise(infoClass), 1000L);
        } else {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                public void onSuccess() { handler.postDelayed(() -> advertise(infoClass), 1000); }
                public void onFailure(int reason) { finish("Initial P2P discovery failed: " + reason); }
            });
        }
        Looper.loop();
    }

    private static void dumpPeers() {
        manager.requestPeers(channel, (WifiP2pDeviceList peers) -> {
            System.out.println("PEERS count=" + peers.getDeviceList().size());
            for (WifiP2pDevice peer : peers.getDeviceList()) {
                System.out.println("PEER " + peer + " wfd=" + peer.getWfdInfo());
            }
        });
        handler.postDelayed(WfdProbe::dumpPeers, 2000L);
    }

    private static void checkStopFile() {
        if (finishing) return;
        if (STOP_FILE.exists()) {
            STOP_FILE.delete();
            finish("Stop requested; restoring original WFD state");
        } else {
            handler.postDelayed(WfdProbe::checkStopFile, 1000L);
        }
    }

    private static void advertise(Class<?> infoClass) {
        manager.requestDeviceInfo(channel, device -> {
            try {
                if (device == null) throw new IllegalStateException("No local P2P device");
                original = device.getClass().getMethod("getWfdInfo").invoke(device);
                if (original == null) original = infoClass.getConstructor().newInstance();
                System.out.println("Device: " + device.deviceName + "; address="
                        + device.deviceAddress + "; original WFD=" + original);
                sinkInfo = infoClass.getConstructor().newInstance();
                infoClass.getMethod("setEnabled", boolean.class).invoke(sinkInfo, true);
                // Advertise the truthful primary-sink profile supported by
                // this receiver; do not claim content-protection/HDCP support.
                infoClass.getMethod("setDeviceType", int.class).invoke(sinkInfo, 1);
                infoClass.getMethod("setSessionAvailable", boolean.class).invoke(sinkInfo, true);
                infoClass.getMethod("setControlPort", int.class).invoke(sinkInfo, 7236);
                infoClass.getMethod("setMaxThroughput", int.class).invoke(sinkInfo, 50);
                setInfo.invoke(manager, channel, sinkInfo, new WifiP2pManager.ActionListener() {
                    public void onSuccess() {
                        changed = true;
                        System.out.println("WFD_SINK_ADVERTISEMENT_ACCEPTED");
                        if (persistent) {
                            scheduleWfdRefresh(30_000L);
                            maintainRadio();
                        } else {
                            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                                public void onSuccess() {
                                    System.out.println("DISCOVERY_STARTED: open DeX search now; this probe does not receive video.");
                                }
                                public void onFailure(int reason) { finish("Discovery failed: " + reason); }
                            });
                        }
                    }
                    public void onFailure(int reason) { finish("WFD advertisement rejected: " + reason); }
                });
            } catch (Exception error) { error.printStackTrace(); finish("Probe failed"); }
        });
    }

    /** Keep the sink listening without disturbing a forming group. */
    private static void maintainRadio() {
        if (!persistent || finishing) return;
        manager.requestNetworkInfo(channel, network -> {
            if (finishing) return;
            if (network != null && network.isConnectedOrConnecting()) {
                System.out.println("P2P session in progress; leaving radio unchanged.");
                scheduleMaintenance(5000L);
                return;
            }
            manager.getListenState(channel, command -> handler.post(command), state -> {
                if (finishing) return;
                if (state == WifiP2pManager.WIFI_P2P_LISTEN_STARTED) {
                    System.out.println("P2P_LISTEN_VERIFIED");
                    scheduleMaintenance(15_000L);
                } else {
                    manager.startListening(channel, new WifiP2pManager.ActionListener() {
                        public void onSuccess() {
                            if (finishing) return;
                            System.out.println("P2P_LISTEN_STARTED; refreshing sink advertisement.");
                            refreshAdvertisement();
                        }
                        public void onFailure(int reason) {
                            System.err.println("P2P listen failed: " + reason);
                            scheduleMaintenance(5000L);
                        }
                    });
                }
            });
        });
    }

    private static void scheduleMaintenance(long delay) {
        if (!finishing) handler.postDelayed(WfdProbe::maintainRadio, delay);
    }

    private static void scheduleWfdRefresh(long delay) {
        if (!finishing) handler.postDelayed(WfdProbe::refreshAdvertisement, delay);
    }

    /** Reapply WFD after enabling a radio that may have been idle-shut down. */
    private static void refreshAdvertisement() {
        if (!persistent || finishing) return;
        try {
            setInfo.invoke(manager, channel, sinkInfo, new WifiP2pManager.ActionListener() {
                public void onSuccess() {
                    System.out.println("WFD sink advertisement refreshed.");
                    scheduleWfdRefresh(30_000L);
                }
                public void onFailure(int reason) {
                    System.err.println("WFD refresh failed: " + reason);
                    if (!finishing) handler.postDelayed(WfdProbe::refreshAdvertisement, 5000L);
                }
            });
        } catch (Exception error) {
            error.printStackTrace();
            if (!finishing) handler.postDelayed(WfdProbe::refreshAdvertisement, 5000L);
        }
    }

    private static void finish(String reason) {
        if (finishing) return;
        finishing = true;
        System.out.println(reason);
        handler.postDelayed(() -> { System.err.println("Cleanup timed out; inspect WFD state"); System.exit(2); }, 5000);
        if (persistent) manager.stopListening(channel, null);
        manager.stopPeerDiscovery(channel, null);
        if (!changed) { channel.close(); System.exit(1); return; }
        try {
            setInfo.invoke(manager, channel, original, new WifiP2pManager.ActionListener() {
                public void onSuccess() {
                    System.out.println("ORIGINAL_WFD_RESTORED");
                    channel.close();
                    System.exit(0);
                }
                public void onFailure(int code) { System.err.println("Restore failed: " + code); channel.close(); System.exit(2); }
            });
        } catch (Exception error) { error.printStackTrace(); System.exit(2); }
    }

}
