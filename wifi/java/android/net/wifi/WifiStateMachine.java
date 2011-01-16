/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi;

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

/**
 * TODO: Add soft AP states as part of WIFI_STATE_XXX
 * Retain WIFI_STATE_ENABLING that indicates driver is loading
 * Add WIFI_STATE_AP_ENABLED to indicate soft AP has started
 * and WIFI_STATE_FAILED for failure
 * Deprecate WIFI_STATE_UNKNOWN
 *
 * Doing this will simplify the logic for sending broadcasts
 */
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.net.LinkAddress;
import android.net.NetworkInfo;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.LinkProperties;
import android.net.wifi.NetworkUpdateResult;
import android.net.wifi.WpsResult.Status;
import android.os.Binder;
import android.os.Message;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Process;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.app.backup.IBackupManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */
public class WifiStateMachine extends HierarchicalStateMachine {

    private static final String TAG = "WifiStateMachine";
    private static final String NETWORKTYPE = "WIFI";
    private static final boolean DBG = false;

    /* TODO: fetch a configurable interface */
    private static final String SOFTAP_IFACE = "wl0.1";

    private WifiMonitor mWifiMonitor;
    private INetworkManagementService nwService;
    private ConnectivityManager mCm;

    /* Scan results handling */
    private List<ScanResult> mScanResults;
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private static final int SCAN_RESULT_CACHE_SIZE = 80;
    private final LinkedHashMap<String, ScanResult> mScanResultCache;

    private String mInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId;
    private boolean mEnableRssiPolling = false;
    private int mRssiPollToken = 0;
    private int mReconnectCount = 0;
    private boolean mIsScanMode = false;

    private boolean mBluetoothConnectionActive = false;

    /**
     * Interval in milliseconds between polling for RSSI
     * and linkspeed information
     */
    private static final int POLL_RSSI_INTERVAL_MSECS = 3000;

    /**
     * Delay between supplicant restarts upon failure to establish connection
     */
    private static final int SUPPLICANT_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart supplicant
     */
    private static final int SUPPLICANT_RESTART_TRIES = 5;

    private int mSupplicantRestartCount = 0;

    private LinkProperties mLinkProperties;

    // Wakelock held during wifi start/stop and driver load/unload
    private PowerManager.WakeLock mWakeLock;

    private Context mContext;

    private DhcpInfo mDhcpInfo;
    private WifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private SupplicantStateTracker mSupplicantStateTracker;
    private WpsStateMachine mWpsStateMachine;

    private AlarmManager mAlarmManager;
    private PendingIntent mScanIntent;
    /* Tracks current frequency mode */
    private AtomicInteger mFrequencyBand = new AtomicInteger(WifiManager.WIFI_FREQUENCY_BAND_AUTO);

    // Channel for sending replies.
    private AsyncChannel mReplyChannel = new AsyncChannel();

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_WIFI_STATE_CHANGED        = 50021;
    private static final int EVENTLOG_WIFI_EVENT_HANDLED        = 50022;
    private static final int EVENTLOG_SUPPLICANT_STATE_CHANGED  = 50023;

    /* Load the driver */
    static final int CMD_LOAD_DRIVER                      = 1;
    /* Unload the driver */
    static final int CMD_UNLOAD_DRIVER                    = 2;
    /* Indicates driver load succeeded */
    static final int CMD_LOAD_DRIVER_SUCCESS              = 3;
    /* Indicates driver load failed */
    static final int CMD_LOAD_DRIVER_FAILURE              = 4;
    /* Indicates driver unload succeeded */
    static final int CMD_UNLOAD_DRIVER_SUCCESS            = 5;
    /* Indicates driver unload failed */
    static final int CMD_UNLOAD_DRIVER_FAILURE            = 6;

    /* Start the supplicant */
    static final int CMD_START_SUPPLICANT                 = 11;
    /* Stop the supplicant */
    static final int CMD_STOP_SUPPLICANT                  = 12;
    /* Start the driver */
    static final int CMD_START_DRIVER                     = 13;
    /* Start the driver */
    static final int CMD_STOP_DRIVER                      = 14;
    /* Indicates DHCP succeded */
    static final int CMD_IP_CONFIG_SUCCESS                = 15;
    /* Indicates DHCP failed */
    static final int CMD_IP_CONFIG_FAILURE                = 16;

    /* Start the soft access point */
    static final int CMD_START_AP                         = 21;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                          = 22;

    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE   = 23;

    /* Supplicant events */
    /* Connection to supplicant established */
    static final int SUP_CONNECTION_EVENT                 = 31;
    /* Connection to supplicant lost */
    static final int SUP_DISCONNECTION_EVENT              = 32;
    /* Driver start completed */
    static final int DRIVER_START_EVENT                   = 33;
    /* Driver stop completed */
    static final int DRIVER_STOP_EVENT                    = 34;
    /* Network connection completed */
    static final int NETWORK_CONNECTION_EVENT             = 36;
    /* Network disconnection completed */
    static final int NETWORK_DISCONNECTION_EVENT          = 37;
    /* Scan results are available */
    static final int SCAN_RESULTS_EVENT                   = 38;
    /* Supplicate state changed */
    static final int SUPPLICANT_STATE_CHANGE_EVENT        = 39;
    /* Password may be incorrect */
    static final int PASSWORD_MAY_BE_INCORRECT_EVENT      = 40;
    /* WPS overlap detected */
    static final int WPS_OVERLAP_EVENT                    = 41;


    /* Supplicant commands */
    /* Is supplicant alive ? */
    static final int CMD_PING_SUPPLICANT                  = 51;
    /* Add/update a network configuration */
    static final int CMD_ADD_OR_UPDATE_NETWORK            = 52;
    /* Delete a network */
    static final int CMD_REMOVE_NETWORK                   = 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    static final int CMD_ENABLE_NETWORK                   = 54;
    /* Enable all networks */
    static final int CMD_ENABLE_ALL_NETWORKS              = 55;
    /* Disable a network. The device does not attempt a connection to the given network. */
    static final int CMD_DISABLE_NETWORK                  = 56;
    /* Blacklist network. De-prioritizes the given BSSID for connection. */
    static final int CMD_BLACKLIST_NETWORK                = 57;
    /* Clear the blacklist network list */
    static final int CMD_CLEAR_BLACKLIST                  = 58;
    /* Save configuration */
    static final int CMD_SAVE_CONFIG                      = 59;

    /* Supplicant commands after driver start*/
    /* Initiate a scan */
    static final int CMD_START_SCAN                       = 71;
    /* Set scan mode. CONNECT_MODE or SCAN_ONLY_MODE */
    static final int CMD_SET_SCAN_MODE                    = 72;
    /* Set scan type. SCAN_ACTIVE or SCAN_PASSIVE */
    static final int CMD_SET_SCAN_TYPE                    = 73;
    /* Disconnect from a network */
    static final int CMD_DISCONNECT                       = 74;
    /* Reconnect to a network */
    static final int CMD_RECONNECT                        = 75;
    /* Reassociate to a network */
    static final int CMD_REASSOCIATE                      = 76;
    /* Controls power mode and suspend mode optimizations
     *
     * When high perf mode is enabled, power mode is set to
     * POWER_MODE_ACTIVE and suspend mode optimizations are disabled
     *
     * When high perf mode is disabled, power mode is set to
     * POWER_MODE_AUTO and suspend mode optimizations are enabled
     *
     * Suspend mode optimizations include:
     * - packet filtering
     * - turn off roaming
     * - DTIM wake up settings
     */
    static final int CMD_SET_HIGH_PERF_MODE               = 77;
    /* Set the country code */
    static final int CMD_SET_COUNTRY_CODE                 = 80;
    /* Request connectivity manager wake lock before driver stop */
    static final int CMD_REQUEST_CM_WAKELOCK              = 81;
    /* Enables RSSI poll */
    static final int CMD_ENABLE_RSSI_POLL                 = 82;
    /* RSSI poll */
    static final int CMD_RSSI_POLL                        = 83;
    /* Set up packet filtering */
    static final int CMD_START_PACKET_FILTERING           = 84;
    /* Clear packet filter */
    static final int CMD_STOP_PACKET_FILTERING            = 85;
    /* Connect to a specified network (network id
     * or WifiConfiguration) This involves increasing
     * the priority of the network, enabling the network
     * (while disabling others) and issuing a reconnect.
     * Note that CMD_RECONNECT just does a reconnect to
     * an existing network. All the networks get enabled
     * upon a successful connection or a failure.
     */
    static final int CMD_CONNECT_NETWORK                  = 86;
    /* Save the specified network. This involves adding
     * an enabled network (if new) and updating the
     * config and issuing a save on supplicant config.
     */
    static final int CMD_SAVE_NETWORK                     = 87;
    /* Delete the specified network. This involves
     * removing the network and issuing a save on
     * supplicant config.
     */
    static final int CMD_FORGET_NETWORK                   = 88;
    /* Start Wi-Fi protected setup */
    static final int CMD_START_WPS                        = 89;
    /* Set the frequency band */
    static final int CMD_SET_FREQUENCY_BAND               = 90;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE           = 111;

    /* Commands/events reported by WpsStateMachine */
    /* Indicates the completion of WPS activity */
    static final int WPS_COMPLETED_EVENT                  = 121;
    /* Reset the WPS state machine */
    static final int CMD_RESET_WPS_STATE                  = 122;

    private static final int CONNECT_MODE   = 1;
    private static final int SCAN_ONLY_MODE = 2;

    private static final int SCAN_ACTIVE = 1;
    private static final int SCAN_PASSIVE = 2;

    private static final int SUCCESS = 1;
    private static final int FAILURE = -1;

    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;

    private static final int POWER_MODE_ACTIVE = 1;
    private static final int POWER_MODE_AUTO = 0;

    /**
     * See {@link Settings.Secure#WIFI_SCAN_INTERVAL_MS}. This is the default value if a
     * Settings.Secure value is not present.
     */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 60 * 1000; /* 1 minute */

    /* Default parent state */
    private HierarchicalState mDefaultState = new DefaultState();
    /* Temporary initial state */
    private HierarchicalState mInitialState = new InitialState();
    /* Unloading the driver */
    private HierarchicalState mDriverUnloadingState = new DriverUnloadingState();
    /* Loading the driver */
    private HierarchicalState mDriverUnloadedState = new DriverUnloadedState();
    /* Driver load/unload failed */
    private HierarchicalState mDriverFailedState = new DriverFailedState();
    /* Driver loading */
    private HierarchicalState mDriverLoadingState = new DriverLoadingState();
    /* Driver loaded */
    private HierarchicalState mDriverLoadedState = new DriverLoadedState();
    /* Driver loaded, waiting for supplicant to start */
    private HierarchicalState mSupplicantStartingState = new SupplicantStartingState();
    /* Driver loaded and supplicant ready */
    private HierarchicalState mSupplicantStartedState = new SupplicantStartedState();
    /* Waiting for supplicant to stop and monitor to exit */
    private HierarchicalState mSupplicantStoppingState = new SupplicantStoppingState();
    /* Driver start issued, waiting for completed event */
    private HierarchicalState mDriverStartingState = new DriverStartingState();
    /* Driver started */
    private HierarchicalState mDriverStartedState = new DriverStartedState();
    /* Driver stopping */
    private HierarchicalState mDriverStoppingState = new DriverStoppingState();
    /* Driver stopped */
    private HierarchicalState mDriverStoppedState = new DriverStoppedState();
    /* Scan for networks, no connection will be established */
    private HierarchicalState mScanModeState = new ScanModeState();
    /* Connecting to an access point */
    private HierarchicalState mConnectModeState = new ConnectModeState();
    /* Fetching IP after network connection (assoc+auth complete) */
    private HierarchicalState mConnectingState = new ConnectingState();
    /* Connected with IP addr */
    private HierarchicalState mConnectedState = new ConnectedState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private HierarchicalState mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private HierarchicalState mDisconnectedState = new DisconnectedState();
    /* Waiting for WPS to be completed*/
    private HierarchicalState mWaitForWpsCompletionState = new WaitForWpsCompletionState();

    /* Soft Ap is running */
    private HierarchicalState mSoftApStartedState = new SoftApStartedState();


    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     *
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    /**
     * One of  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     *
     */
    private final AtomicInteger mWifiApState = new AtomicInteger(WIFI_AP_STATE_DISABLED);

    private final AtomicInteger mLastEnableUid = new AtomicInteger(Process.myUid());
    private final AtomicInteger mLastApEnableUid = new AtomicInteger(Process.myUid());

    private static final int SCAN_REQUEST = 0;
    private static final String ACTION_START_SCAN =
        "com.android.server.WifiManager.action.START_SCAN";

    /**
     * Keep track of whether WIFI is running.
     */
    private boolean mIsRunning = false;

    /**
     * Keep track of whether we last told the battery stats we had started.
     */
    private boolean mReportedRunning = false;

    /**
     * Most recently set source of starting WIFI.
     */
    private final WorkSource mRunningWifiUids = new WorkSource();

    /**
     * The last reported UIDs that were responsible for starting WIFI.
     */
    private final WorkSource mLastRunningWifiUids = new WorkSource();

    private final IBatteryStats mBatteryStats;

    public WifiStateMachine(Context context) {
        super(TAG);

        mContext = context;

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        nwService = INetworkManagementService.Stub.asInterface(b);

        mWifiMonitor = new WifiMonitor(this);
        mDhcpInfo = new DhcpInfo();
        mWifiInfo = new WifiInfo();
        mInterfaceName = SystemProperties.get("wifi.interface", "tiwlan0");
        mSupplicantStateTracker = new SupplicantStateTracker(context, this, getHandler());
        mWpsStateMachine = new WpsStateMachine(context, this, getHandler());
        mLinkProperties = new LinkProperties();

        mNetworkInfo.setIsAvailable(false);
        mLinkProperties.clear();
        mLastBssid = null;
        mLastNetworkId = -1;
        mLastSignalLevel = -1;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent scanIntent = new Intent(ACTION_START_SCAN, null);
        mScanIntent = PendingIntent.getBroadcast(mContext, SCAN_REQUEST, scanIntent, 0);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        startScan(false);
                    }
                },
                new IntentFilter(ACTION_START_SCAN));

        mScanResultCache = new LinkedHashMap<String, ScanResult>(
            SCAN_RESULT_CACHE_SIZE, 0.75f, true) {
                /*
                 * Limit the cache size by SCAN_RESULT_CACHE_SIZE
                 * elements
                 */
                @Override
                public boolean removeEldestEntry(Map.Entry eldest) {
                    return SCAN_RESULT_CACHE_SIZE < this.size();
                }
        };

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        addState(mDefaultState);
            addState(mInitialState, mDefaultState);
            addState(mDriverUnloadingState, mDefaultState);
            addState(mDriverUnloadedState, mDefaultState);
                addState(mDriverFailedState, mDriverUnloadedState);
            addState(mDriverLoadingState, mDefaultState);
            addState(mDriverLoadedState, mDefaultState);
            addState(mSupplicantStartingState, mDefaultState);
            addState(mSupplicantStartedState, mDefaultState);
                addState(mDriverStartingState, mSupplicantStartedState);
                addState(mDriverStartedState, mSupplicantStartedState);
                    addState(mScanModeState, mDriverStartedState);
                    addState(mConnectModeState, mDriverStartedState);
                        addState(mConnectingState, mConnectModeState);
                        addState(mConnectedState, mConnectModeState);
                        addState(mDisconnectingState, mConnectModeState);
                        addState(mDisconnectedState, mConnectModeState);
                        addState(mWaitForWpsCompletionState, mConnectModeState);
                addState(mDriverStoppingState, mSupplicantStartedState);
                addState(mDriverStoppedState, mSupplicantStartedState);
            addState(mSupplicantStoppingState, mDefaultState);
            addState(mSoftApStartedState, mDefaultState);

        setInitialState(mInitialState);

        if (DBG) setDbg(true);

        //start the state machine
        start();
    }

    /*********************************************************
     * Methods exposed for public use
     ********************************************************/

    /**
     * TODO: doc
     */
    public boolean syncPingSupplicant(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_PING_SUPPLICANT);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * TODO: doc
     */
    public void startScan(boolean forceActive) {
        sendMessage(obtainMessage(CMD_START_SCAN, forceActive ?
                SCAN_ACTIVE : SCAN_PASSIVE, 0));
    }

    /**
     * TODO: doc
     */
    public void setWifiEnabled(boolean enable) {
        mLastEnableUid.set(Binder.getCallingUid());
        if (enable) {
            /* Argument is the state that is entered prior to load */
            sendMessage(obtainMessage(CMD_LOAD_DRIVER, WIFI_STATE_ENABLING, 0));
            sendMessage(CMD_START_SUPPLICANT);
        } else {
            sendMessage(CMD_STOP_SUPPLICANT);
            /* Argument is the state that is entered upon success */
            sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_DISABLED, 0));
        }
    }

    /**
     * TODO: doc
     */
    public void setWifiApEnabled(WifiConfiguration wifiConfig, boolean enable) {
        mLastApEnableUid.set(Binder.getCallingUid());
        if (enable) {
            /* Argument is the state that is entered prior to load */
            sendMessage(obtainMessage(CMD_LOAD_DRIVER, WIFI_AP_STATE_ENABLING, 0));
            sendMessage(obtainMessage(CMD_START_AP, wifiConfig));
        } else {
            sendMessage(CMD_STOP_AP);
            /* Argument is the state that is entered upon success */
            sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_DISABLED, 0));
        }
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiStateByName() {
        switch (mWifiState.get()) {
            case WIFI_STATE_DISABLING:
                return "disabling";
            case WIFI_STATE_DISABLED:
                return "disabled";
            case WIFI_STATE_ENABLING:
                return "enabling";
            case WIFI_STATE_ENABLED:
                return "enabled";
            case WIFI_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiApState() {
        return mWifiApState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiApStateByName() {
        switch (mWifiApState.get()) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }

    /**
     * Get status information for the current connection, if any.
     * @return a {@link WifiInfo} object containing information about the current connection
     *
     */
    public WifiInfo syncRequestConnectionInfo() {
        return mWifiInfo;
    }

    public DhcpInfo syncGetDhcpInfo() {
        synchronized (mDhcpInfo) {
            return new DhcpInfo(mDhcpInfo);
        }
    }

    /**
     * TODO: doc
     */
    public void setDriverStart(boolean enable) {
        if (enable) {
            sendMessage(CMD_START_DRIVER);
        } else {
            sendMessage(CMD_STOP_DRIVER);
        }
    }

    /**
     * TODO: doc
     */
    public void setScanOnlyMode(boolean enable) {
      if (enable) {
          sendMessage(obtainMessage(CMD_SET_SCAN_MODE, SCAN_ONLY_MODE, 0));
      } else {
          sendMessage(obtainMessage(CMD_SET_SCAN_MODE, CONNECT_MODE, 0));
      }
    }

    /**
     * TODO: doc
     */
    public void setScanType(boolean active) {
      if (active) {
          sendMessage(obtainMessage(CMD_SET_SCAN_TYPE, SCAN_ACTIVE, 0));
      } else {
          sendMessage(obtainMessage(CMD_SET_SCAN_TYPE, SCAN_PASSIVE, 0));
      }
    }

    /**
     * TODO: doc
     */
    public List<ScanResult> syncGetScanResultsList() {
        return mScanResults;
    }

    /**
     * Disconnect from Access Point
     */
    public void disconnectCommand() {
        sendMessage(CMD_DISCONNECT);
    }

    /**
     * Initiate a reconnection to AP
     */
    public void reconnectCommand() {
        sendMessage(CMD_RECONNECT);
    }

    /**
     * Initiate a re-association to AP
     */
    public void reassociateCommand() {
        sendMessage(CMD_REASSOCIATE);
    }

    /**
     * Add a network synchronously
     *
     * @return network id of the new network
     */
    public int syncAddOrUpdateNetwork(AsyncChannel channel, WifiConfiguration config) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_NETWORK, config);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    public List<WifiConfiguration> syncGetConfiguredNetworks() {
        return WifiConfigStore.getConfiguredNetworks();
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     */
    public boolean syncRemoveNetwork(AsyncChannel channel, int networkId) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_REMOVE_NETWORK, networkId);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Enable a network
     *
     * @param netId network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(AsyncChannel channel, int netId, boolean disableOthers) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ENABLE_NETWORK, netId,
                disableOthers ? 1 : 0);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncDisableNetwork(AsyncChannel channel, int netId) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_DISABLE_NETWORK, netId);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Blacklist a BSSID. This will avoid the AP if there are
     * alternate APs to connect
     *
     * @param bssid BSSID of the network
     */
    public void addToBlacklist(String bssid) {
        sendMessage(obtainMessage(CMD_BLACKLIST_NETWORK, bssid));
    }

    /**
     * Clear the blacklist list
     *
     */
    public void clearBlacklist() {
        sendMessage(obtainMessage(CMD_CLEAR_BLACKLIST));
    }

    public void connectNetwork(int netId) {
        sendMessage(obtainMessage(CMD_CONNECT_NETWORK, netId, 0));
    }

    public void connectNetwork(WifiConfiguration wifiConfig) {
        /* arg1 is used to indicate netId, force a netId value of -1 when
         * we are passing a configuration since the default value of
         * 0 is a valid netId
         */
        sendMessage(obtainMessage(CMD_CONNECT_NETWORK, -1, 0, wifiConfig));
    }

    public void saveNetwork(WifiConfiguration wifiConfig) {
        sendMessage(obtainMessage(CMD_SAVE_NETWORK, wifiConfig));
    }

    public void forgetNetwork(int netId) {
        sendMessage(obtainMessage(CMD_FORGET_NETWORK, netId, 0));
    }

    public WpsResult startWps(AsyncChannel channel, WpsConfiguration config) {
        WpsResult result;
        switch (config.setup) {
            case PIN_FROM_DEVICE:
            case PBC:
            case PIN_FROM_ACCESS_POINT:
                //TODO: will go away with AsyncChannel use from settings
                Message resultMsg = channel.sendMessageSynchronously(CMD_START_WPS, config);
                result = (WpsResult) resultMsg.obj;
                resultMsg.recycle();
                break;
            default:
                result = new WpsResult(Status.FAILURE);
                break;
        }
        return result;
    }

    public void enableRssiPolling(boolean enabled) {
       sendMessage(obtainMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0));
    }

    public void enableAllNetworks() {
        sendMessage(CMD_ENABLE_ALL_NETWORKS);
    }

    /**
     * Start packet filtering
     */
    public void startPacketFiltering() {
        sendMessage(CMD_START_PACKET_FILTERING);
    }

    /**
     * Stop packet filtering
     */
    public void stopPacketFiltering() {
        sendMessage(CMD_STOP_PACKET_FILTERING);
    }

    /**
     * Set high performance mode of operation.
     * Enabling would set active power mode and disable suspend optimizations;
     * disabling would set auto power mode and enable suspend optimizations
     * @param enable true if enable, false otherwise
     */
    public void setHighPerfModeEnabled(boolean enable) {
        sendMessage(obtainMessage(CMD_SET_HIGH_PERF_MODE, enable ? 1 : 0, 0));
    }

    /**
     * Set the country code
     * @param countryCode following ISO 3166 format
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setCountryCode(String countryCode, boolean persist) {
        if (persist) {
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.WIFI_COUNTRY_CODE,
                    countryCode);
        }
        sendMessage(obtainMessage(CMD_SET_COUNTRY_CODE, countryCode));
    }

    /**
     * Set the operational frequency band
     * @param band
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setFrequencyBand(int band, boolean persist) {
        if (persist) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_FREQUENCY_BAND,
                    band);
        }
        sendMessage(obtainMessage(CMD_SET_FREQUENCY_BAND, band, 0));
    }

    /**
     * Returns the operational frequency band
     */
    public int getFrequencyBand() {
        return mFrequencyBand.get();
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(obtainMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0));
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * TODO: deprecate this
     */
    public boolean syncSaveConfig(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_SAVE_CONFIG);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Request a wakelock with connectivity service to
     * keep the device awake until we hand-off from wifi
     * to an alternate network
     */
    public void requestCmWakeLock() {
        sendMessage(CMD_REQUEST_CM_WAKELOCK);
    }

    public void updateBatteryWorkSource(WorkSource newSource) {
        synchronized (mRunningWifiUids) {
            try {
                if (newSource != null) {
                    mRunningWifiUids.set(newSource);
                }
                if (mIsRunning) {
                    if (mReportedRunning) {
                        // If the work source has changed since last time, need
                        // to remove old work from battery stats.
                        if (mLastRunningWifiUids.diff(mRunningWifiUids)) {
                            mBatteryStats.noteWifiRunningChanged(mLastRunningWifiUids,
                                    mRunningWifiUids);
                            mLastRunningWifiUids.set(mRunningWifiUids);
                        }
                    } else {
                        // Now being started, report it.
                        mBatteryStats.noteWifiRunning(mRunningWifiUids);
                        mLastRunningWifiUids.set(mRunningWifiUids);
                        mReportedRunning = true;
                    }
                } else {
                    if (mReportedRunning) {
                        // Last reported we were running, time to stop.
                        mBatteryStats.noteWifiStopped(mLastRunningWifiUids);
                        mLastRunningWifiUids.clear();
                        mReportedRunning = false;
                    }
                }
                mWakeLock.setWorkSource(newSource);
            } catch (RemoteException ignore) {
            }
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String LS = System.getProperty("line.separator");
        sb.append("current HSM state: ").append(getCurrentState().getName()).append(LS);
        sb.append("mLinkProperties ").append(mLinkProperties).append(LS);
        sb.append("mWifiInfo ").append(mWifiInfo).append(LS);
        sb.append("mDhcpInfo ").append(mDhcpInfo).append(LS);
        sb.append("mNetworkInfo ").append(mNetworkInfo).append(LS);
        sb.append("mLastSignalLevel ").append(mLastSignalLevel).append(LS);
        sb.append("mLastBssid ").append(mLastBssid).append(LS);
        sb.append("mLastNetworkId ").append(mLastNetworkId).append(LS);
        sb.append("mReconnectCount ").append(mReconnectCount).append(LS);
        sb.append("mIsScanMode ").append(mIsScanMode).append(LS);
        sb.append("Supplicant status").append(LS)
                .append(WifiNative.statusCommand()).append(LS).append(LS);

        sb.append(WifiConfigStore.dump());
        return sb.toString();
    }

    /*********************************************************
     * Internal private functions
     ********************************************************/

    /**
     * Set the country code from the system setting value, if any.
     */
    private void setCountryCode() {
        String countryCode = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.WIFI_COUNTRY_CODE);
        if (countryCode != null && !countryCode.isEmpty()) {
            setCountryCode(countryCode, false);
        } else {
            //use driver default
        }
    }

    /**
     * Set the frequency band from the system setting value, if any.
     */
    private void setFrequencyBand() {
        int band = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.WIFI_FREQUENCY_BAND, WifiManager.WIFI_FREQUENCY_BAND_AUTO);
        setFrequencyBand(band, false);
    }

    private void setWifiState(int wifiState) {
        final int previousWifiState = mWifiState.get();

        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }

        mWifiState.set(wifiState);

        if (DBG) Log.d(TAG, "setWifiState: " + syncGetWifiStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcast(intent);
    }

    private void setWifiApState(int wifiApState) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) Log.d(TAG, "setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Parse the scan result line passed to us by wpa_supplicant (helper).
     * @param line the line to parse
     * @return the {@link ScanResult} object
     */
    private ScanResult parseScanResult(String line) {
        ScanResult scanResult = null;
        if (line != null) {
            /*
             * Cache implementation (LinkedHashMap) is not synchronized, thus,
             * must synchronized here!
             */
            synchronized (mScanResultCache) {
                String[] result = scanResultPattern.split(line);
                if (3 <= result.length && result.length <= 5) {
                    String bssid = result[0];
                    // bssid | frequency | level | flags | ssid
                    int frequency;
                    int level;
                    try {
                        frequency = Integer.parseInt(result[1]);
                        level = Integer.parseInt(result[2]);
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch (NumberFormatException e) {
                        frequency = 0;
                        level = 0;
                    }

                    /*
                     * The formatting of the results returned by
                     * wpa_supplicant is intended to make the fields
                     * line up nicely when printed,
                     * not to make them easy to parse. So we have to
                     * apply some heuristics to figure out which field
                     * is the SSID and which field is the flags.
                     */
                    String ssid;
                    String flags;
                    if (result.length == 4) {
                        if (result[3].charAt(0) == '[') {
                            flags = result[3];
                            ssid = "";
                        } else {
                            flags = "";
                            ssid = result[3];
                        }
                    } else if (result.length == 5) {
                        flags = result[3];
                        ssid = result[4];
                    } else {
                        // Here, we must have 3 fields: no flags and ssid
                        // set
                        flags = "";
                        ssid = "";
                    }

                    // bssid + ssid is the hash key
                    String key = bssid + ssid;
                    scanResult = mScanResultCache.get(key);
                    if (scanResult != null) {
                        scanResult.level = level;
                        scanResult.SSID = ssid;
                        scanResult.capabilities = flags;
                        scanResult.frequency = frequency;
                    } else {
                        // Do not add scan results that have no SSID set
                        if (0 < ssid.trim().length()) {
                            scanResult =
                                new ScanResult(
                                    ssid, bssid, flags, level, frequency);
                            mScanResultCache.put(key, scanResult);
                        }
                    }
                } else {
                    Log.w(TAG, "Misformatted scan result text with " +
                          result.length + " fields: " + line);
                }
            }
        }

        return scanResult;
    }

    /**
     * scanResults input format
     * 00:bb:cc:dd:cc:ee       2427    166     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net1
     * 00:bb:cc:dd:cc:ff       2412    165     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net2
     */
    private void setScanResults(String scanResults) {
        if (scanResults == null) {
            return;
        }

        List<ScanResult> scanList = new ArrayList<ScanResult>();

        int lineCount = 0;

        int scanResultsLen = scanResults.length();
        // Parse the result string, keeping in mind that the last line does
        // not end with a newline.
        for (int lineBeg = 0, lineEnd = 0; lineEnd <= scanResultsLen; ++lineEnd) {
            if (lineEnd == scanResultsLen || scanResults.charAt(lineEnd) == '\n') {
                ++lineCount;

                if (lineCount == 1) {
                    lineBeg = lineEnd + 1;
                    continue;
                }
                if (lineEnd > lineBeg) {
                    String line = scanResults.substring(lineBeg, lineEnd);
                    ScanResult scanResult = parseScanResult(line);
                    if (scanResult != null) {
                        scanList.add(scanResult);
                    } else {
                        //TODO: hidden network handling
                    }
                }
                lineBeg = lineEnd + 1;
            }
        }

        mScanResults = scanList;
    }

    private String fetchSSID() {
        String status = WifiNative.statusCommand();
        if (status == null) {
            return null;
        }
        // extract ssid from a series of "name=value"
        String[] lines = status.split("\n");
        for (String line : lines) {
            String[] prop = line.split(" *= *");
            if (prop.length < 2) continue;
            String name = prop[0];
            String value = prop[1];
            if (name.equalsIgnoreCase("ssid")) return value;
        }
        return null;
    }

    /*
     * Fetch RSSI and linkspeed on current connection
     */
    private void fetchRssiAndLinkSpeedNative() {
        int newRssi = WifiNative.getRssiCommand();
        if (newRssi != -1 && -200 < newRssi && newRssi < 256) { // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) newRssi -= 256;
            mWifiInfo.setRssi(newRssi);
            /*
             * Rather then sending the raw RSSI out every time it
             * changes, we precalculate the signal level that would
             * be displayed in the status bar, and only send the
             * broadcast if that much more coarse-grained number
             * changes. This cuts down greatly on the number of
             * broadcasts, at the cost of not mWifiInforming others
             * interested in RSSI of all the changes in signal
             * level.
             */
            // TODO: The second arg to the call below needs to be a symbol somewhere, but
            // it's actually the size of an array of icons that's private
            // to StatusBar Policy.
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi, 4);
            if (newSignalLevel != mLastSignalLevel) {
                sendRssiChangeBroadcast(newRssi);
            }
            mLastSignalLevel = newSignalLevel;
        } else {
            mWifiInfo.setRssi(-200);
        }
        int newLinkSpeed = WifiNative.getLinkSpeedCommand();
        if (newLinkSpeed != -1) {
            mWifiInfo.setLinkSpeed(newLinkSpeed);
        }
    }

    private void setHighPerfModeEnabledNative(boolean enable) {
        if(!WifiNative.setSuspendOptimizationsCommand(!enable)) {
            Log.e(TAG, "set suspend optimizations failed!");
        }
        if (enable) {
            if (!WifiNative.setPowerModeCommand(POWER_MODE_ACTIVE)) {
                Log.e(TAG, "set power mode active failed!");
            }
        } else {
            if (!WifiNative.setPowerModeCommand(POWER_MODE_AUTO)) {
                Log.e(TAG, "set power mode auto failed!");
            }
        }
    }

    private void configureLinkProperties() {
        if (WifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
            mLinkProperties = WifiConfigStore.getLinkProperties(mLastNetworkId);
        } else {
            // TODO - fix this for v6
            synchronized (mDhcpInfo) {
                mLinkProperties.addLinkAddress(new LinkAddress(
                        NetworkUtils.intToInetAddress(mDhcpInfo.ipAddress),
                        NetworkUtils.intToInetAddress(mDhcpInfo.netmask)));
                mLinkProperties.setGateway(NetworkUtils.intToInetAddress(mDhcpInfo.gateway));
                mLinkProperties.addDns(NetworkUtils.intToInetAddress(mDhcpInfo.dns1));
                mLinkProperties.addDns(NetworkUtils.intToInetAddress(mDhcpInfo.dns2));
            }
            mLinkProperties.setHttpProxy(WifiConfigStore.getProxyProperties(mLastNetworkId));
        }
        mLinkProperties.setInterfaceName(mInterfaceName);
        Log.d(TAG, "netId=" + mLastNetworkId  + " Link configured: " + mLinkProperties.toString());
    }

    private int getMaxDhcpRetries() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                                      Settings.Secure.WIFI_MAX_DHCP_RETRY_COUNT,
                                      DEFAULT_MAX_DHCP_RETRIES);
    }

    private void sendScanResultsAvailableBroadcast() {
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendBroadcast(intent);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, mLinkProperties);
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        mContext.sendStickyBroadcast(intent);
    }

    private void sendErrorBroadcast(int errorCode) {
        Intent intent = new Intent(WifiManager.ERROR_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_ERROR_CODE, errorCode);
        mContext.sendBroadcast(intent);
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, mLinkProperties);
        mContext.sendBroadcast(intent);
    }

    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcast(intent);
    }

    /**
     * Record the detailed state of a network.
     * @param state the new @{code DetailedState}
     */
    private void setNetworkDetailedState(NetworkInfo.DetailedState state) {
        Log.d(TAG, "setDetailed state, old ="
                + mNetworkInfo.getDetailedState() + " and new state=" + state);
        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, null);
        }
    }

    private DetailedState getNetworkDetailedState() {
        return mNetworkInfo.getDetailedState();
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        Log.d(TAG, "Reset connections and stopping DHCP");

        /*
         * Reset connections & stop DHCP
         */
        NetworkUtils.resetConnections(mInterfaceName);

        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Log.e(TAG, "Could not stop DHCP");
        }

        /* Disable interface */
        NetworkUtils.disableInterface(mInterfaceName);

        /* send event to CM & network change broadcast */
        setNetworkDetailedState(DetailedState.DISCONNECTED);
        sendNetworkStateChangeBroadcast(mLastBssid);

        /* Reset data structures */
        mWifiInfo.setIpAddress(0);
        mWifiInfo.setBSSID(null);
        mWifiInfo.setSSID(null);
        mWifiInfo.setNetworkId(-1);

        /* Clear network properties */
        mLinkProperties.clear();

        mLastBssid= null;
        mLastNetworkId = -1;

    }


    /*********************************************************
     * Notifications from WifiMonitor
     ********************************************************/

    /**
     * Stores supplicant state change information passed from WifiMonitor
     */
    static class StateChangeResult {
        StateChangeResult(int networkId, String BSSID, SupplicantState state) {
            this.state = state;
            this.BSSID = BSSID;
            this.networkId = networkId;
        }
        int networkId;
        String BSSID;
        SupplicantState state;
    }

    /**
     * Send the tracker a notification that a user-entered password key
     * may be incorrect (i.e., caused authentication to fail).
     */
    void notifyPasswordKeyMayBeIncorrect() {
        sendMessage(PASSWORD_MAY_BE_INCORRECT_EVENT);
    }

    /**
     * Send a notification that the supplicant has detected overlapped
     * WPS sessions
     */
    void notifyWpsOverlap() {
        sendMessage(WPS_OVERLAP_EVENT);
    }

    /**
     * Send the tracker a notification that a connection to the supplicant
     * daemon has been established.
     */
    void notifySupplicantConnection() {
        sendMessage(SUP_CONNECTION_EVENT);
    }

    /**
     * Send the tracker a notification that connection to the supplicant
     * daemon is lost
     */
    void notifySupplicantLost() {
        sendMessage(SUP_DISCONNECTION_EVENT);
    }

    /**
     * Send the tracker a notification that the state of Wifi connectivity
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new network state
     * @param BSSID when the new state is {@link DetailedState#CONNECTED
     * NetworkInfo.DetailedState.CONNECTED},
     * this is the MAC address of the access point. Otherwise, it
     * is {@code null}.
     */
    void notifyNetworkStateChange(DetailedState newState, String BSSID, int networkId) {
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            sendMessage(obtainMessage(NETWORK_CONNECTION_EVENT, networkId, 0, BSSID));
        } else {
            sendMessage(obtainMessage(NETWORK_DISCONNECTION_EVENT, networkId, 0, BSSID));
        }
    }

    /**
     * Send the tracker a notification that the state of the supplicant
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new {@code SupplicantState}
     */
    void notifySupplicantStateChange(int networkId, String BSSID, SupplicantState newState) {
        sendMessage(obtainMessage(SUPPLICANT_STATE_CHANGE_EVENT,
                new StateChangeResult(networkId, BSSID, newState)));
    }

    /**
     * Send the tracker a notification that a scan has completed, and results
     * are available.
     */
    void notifyScanResultsAvailable() {
        /**
         * Switch scan mode over to passive.
         * Turning off scan-only mode happens only in "Connect" mode
         */
        setScanType(false);
        sendMessage(SCAN_RESULTS_EVENT);
    }

    void notifyDriverStarted() {
        sendMessage(DRIVER_START_EVENT);
    }

    void notifyDriverStopped() {
        sendMessage(DRIVER_STOP_EVENT);
    }

    void notifyDriverHung() {
        setWifiEnabled(false);
        setWifiEnabled(true);
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends HierarchicalState {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    break;
                    /* Synchronous call returns */
                case CMD_PING_SUPPLICANT:
                case CMD_ENABLE_NETWORK:
                case CMD_DISABLE_NETWORK:
                case CMD_ADD_OR_UPDATE_NETWORK:
                case CMD_REMOVE_NETWORK:
                case CMD_SAVE_CONFIG:
                    mReplyChannel.replyToMessage(message, message.what, FAILURE);
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    break;
                    /* Discard */
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case SUP_CONNECTION_EVENT:
                case SUP_DISCONNECTION_EVENT:
                case DRIVER_START_EVENT:
                case DRIVER_STOP_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                case SCAN_RESULTS_EVENT:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                case WPS_OVERLAP_EVENT:
                case CMD_BLACKLIST_NETWORK:
                case CMD_CLEAR_BLACKLIST:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_REQUEST_CM_WAKELOCK:
                case CMD_CONNECT_NETWORK:
                case CMD_SAVE_NETWORK:
                case CMD_FORGET_NETWORK:
                case CMD_RSSI_POLL:
                case CMD_ENABLE_ALL_NETWORKS:
                    break;
                case CMD_START_WPS:
                    /* Return failure when the state machine cannot handle WPS initiation*/
                    mReplyChannel.replyToMessage(message, message.what,
                                new WpsResult(Status.FAILURE));
                    break;
                default:
                    Log.e(TAG, "Error! unhandled message" + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InitialState extends HierarchicalState {
        @Override
        //TODO: could move logging into a common class
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            // [31-8] Reserved for future use
            // [7 - 0] HSM state change
            // 50021 wifi_state_changed (custom|1|5)
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            if (WifiNative.isDriverLoaded()) {
                transitionTo(mDriverLoadedState);
            }
            else {
                transitionTo(mDriverUnloadedState);
            }
        }
    }

    class DriverLoadingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            /* TODO: add a timeout to fail when driver load is hung.
             * Similarly for driver unload.
             */
            new Thread(new Runnable() {
                public void run() {
                    mWakeLock.acquire();
                    //enabling state
                    switch(message.arg1) {
                        case WIFI_STATE_ENABLING:
                            setWifiState(WIFI_STATE_ENABLING);
                            break;
                        case WIFI_AP_STATE_ENABLING:
                            setWifiApState(WIFI_AP_STATE_ENABLING);
                            break;
                    }

                    if(WifiNative.loadDriver()) {
                        Log.d(TAG, "Driver load successful");
                        sendMessage(CMD_LOAD_DRIVER_SUCCESS);
                    } else {
                        Log.e(TAG, "Failed to load driver!");
                        switch(message.arg1) {
                            case WIFI_STATE_ENABLING:
                                setWifiState(WIFI_STATE_UNKNOWN);
                                break;
                            case WIFI_AP_STATE_ENABLING:
                                setWifiApState(WIFI_AP_STATE_FAILED);
                                break;
                        }
                        sendMessage(CMD_LOAD_DRIVER_FAILURE);
                    }
                    mWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_LOAD_DRIVER_SUCCESS:
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_LOAD_DRIVER_FAILURE:
                    transitionTo(mDriverFailedState);
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverLoadedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_UNLOAD_DRIVER:
                    transitionTo(mDriverUnloadingState);
                    break;
                case CMD_START_SUPPLICANT:
                    if(WifiNative.startSupplicant()) {
                        Log.d(TAG, "Supplicant start successful");
                        mWifiMonitor.startMonitoring();
                        transitionTo(mSupplicantStartingState);
                    } else {
                        Log.e(TAG, "Failed to start supplicant!");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_START_AP:
                    try {
                        nwService.startAccessPoint((WifiConfiguration) message.obj,
                                    mInterfaceName,
                                    SOFTAP_IFACE);
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in startAccessPoint()");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_FAILED, 0));
                        break;
                    }
                    Log.d(TAG, "Soft AP start successful");
                    setWifiApState(WIFI_AP_STATE_ENABLED);
                    transitionTo(mSoftApStartedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverUnloadingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            new Thread(new Runnable() {
                public void run() {
                    if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
                    mWakeLock.acquire();
                    if(WifiNative.unloadDriver()) {
                        Log.d(TAG, "Driver unload successful");
                        sendMessage(CMD_UNLOAD_DRIVER_SUCCESS);

                        switch(message.arg1) {
                            case WIFI_STATE_DISABLED:
                            case WIFI_STATE_UNKNOWN:
                                setWifiState(message.arg1);
                                break;
                            case WIFI_AP_STATE_DISABLED:
                            case WIFI_AP_STATE_FAILED:
                                setWifiApState(message.arg1);
                                break;
                        }
                    } else {
                        Log.e(TAG, "Failed to unload driver!");
                        sendMessage(CMD_UNLOAD_DRIVER_FAILURE);

                        switch(message.arg1) {
                            case WIFI_STATE_DISABLED:
                            case WIFI_STATE_UNKNOWN:
                                setWifiState(WIFI_STATE_UNKNOWN);
                                break;
                            case WIFI_AP_STATE_DISABLED:
                            case WIFI_AP_STATE_FAILED:
                                setWifiApState(WIFI_AP_STATE_FAILED);
                                break;
                        }
                    }
                    mWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_UNLOAD_DRIVER_SUCCESS:
                    transitionTo(mDriverUnloadedState);
                    break;
                case CMD_UNLOAD_DRIVER_FAILURE:
                    transitionTo(mDriverFailedState);
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverUnloadedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_LOAD_DRIVER:
                    transitionTo(mDriverLoadingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverFailedState extends HierarchicalState {
        @Override
        public void enter() {
            Log.e(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            return NOT_HANDLED;
        }
    }


    class SupplicantStartingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case SUP_CONNECTION_EVENT:
                    Log.d(TAG, "Supplicant connection established");
                    setWifiState(WIFI_STATE_ENABLED);
                    mSupplicantRestartCount = 0;
                    /* Reset the supplicant state to indicate the supplicant
                     * state is not known at this time */
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    mWpsStateMachine.sendMessage(CMD_RESET_WPS_STATE);
                    /* Initialize data structures */
                    mLastBssid = null;
                    mLastNetworkId = -1;
                    mLastSignalLevel = -1;

                    mWifiInfo.setMacAddress(WifiNative.getMacAddressCommand());

                    WifiConfigStore.initialize(mContext);

                    //TODO: initialize and fix multicast filtering
                    //mWM.initializeMulticastFiltering();

                    sendSupplicantConnectionChangedBroadcast(true);
                    transitionTo(mDriverStartedState);
                    break;
                case SUP_DISCONNECTION_EVENT:
                    if (++mSupplicantRestartCount <= SUPPLICANT_RESTART_TRIES) {
                        Log.e(TAG, "Failed to setup control channel, restart supplicant");
                        WifiNative.killSupplicant();
                        transitionTo(mDriverLoadedState);
                        sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    } else {
                        mSupplicantRestartCount = 0;
                        Log.e(TAG, "Failed " + mSupplicantRestartCount +
                                " times to start supplicant, unload driver");
                        transitionTo(mDriverLoadedState);
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class SupplicantStartedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            /* Initialize for connect mode operation at start */
            mIsScanMode = false;
            /* Wifi is available as long as we have a connection to supplicant */
            mNetworkInfo.setIsAvailable(true);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            WifiConfiguration config;
            boolean eventLoggingEnabled = true;
            switch(message.what) {
                case CMD_STOP_SUPPLICANT:   /* Supplicant stopped by user */
                    Log.d(TAG, "stopping supplicant");
                    if (!WifiNative.stopSupplicant()) {
                        Log.e(TAG, "Failed to stop supplicant, issue kill");
                        WifiNative.killSupplicant();
                    }
                    mNetworkInfo.setIsAvailable(false);
                    handleNetworkDisconnect();
                    setWifiState(WIFI_STATE_DISABLING);
                    sendSupplicantConnectionChangedBroadcast(false);
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    mWpsStateMachine.sendMessage(CMD_RESET_WPS_STATE);
                    transitionTo(mSupplicantStoppingState);
                    break;
                case SUP_DISCONNECTION_EVENT:  /* Supplicant connection lost */
                    Log.e(TAG, "Connection lost, restart supplicant");
                    WifiNative.killSupplicant();
                    WifiNative.closeSupplicantConnection();
                    mNetworkInfo.setIsAvailable(false);
                    handleNetworkDisconnect();
                    sendSupplicantConnectionChangedBroadcast(false);
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    mWpsStateMachine.sendMessage(CMD_RESET_WPS_STATE);
                    transitionTo(mDriverLoadedState);
                    sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    break;
                case SCAN_RESULTS_EVENT:
                    eventLoggingEnabled = false;
                    setScanResults(WifiNative.scanResultsCommand());
                    sendScanResultsAvailableBroadcast();
                    break;
                case CMD_PING_SUPPLICANT:
                    boolean ok = WifiNative.pingCommand();
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    mReplyChannel.replyToMessage(message, CMD_ADD_OR_UPDATE_NETWORK,
                            WifiConfigStore.addOrUpdateNetwork(config));
                    break;
                case CMD_REMOVE_NETWORK:
                    ok = WifiConfigStore.removeNetwork(message.arg1);
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_NETWORK:
                    ok = WifiConfigStore.enableNetwork(message.arg1, message.arg2 == 1);
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_ALL_NETWORKS:
                    WifiConfigStore.enableAllNetworks();
                    break;
                case CMD_DISABLE_NETWORK:
                    ok = WifiConfigStore.disableNetwork(message.arg1);
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_BLACKLIST_NETWORK:
                    WifiNative.addToBlacklistCommand((String)message.obj);
                    break;
                case CMD_CLEAR_BLACKLIST:
                    WifiNative.clearBlacklistCommand();
                    break;
                case CMD_SAVE_CONFIG:
                    ok = WifiConfigStore.saveConfig();
                    mReplyChannel.replyToMessage(message, CMD_SAVE_CONFIG, ok ? SUCCESS : FAILURE);

                    // Inform the backup manager about a data change
                    IBackupManager ibm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (ibm != null) {
                        try {
                            ibm.dataChanged("com.android.providers.settings");
                        } catch (Exception e) {
                            // Try again later
                        }
                    }
                    break;
                    /* Cannot start soft AP while in client mode */
                case CMD_START_AP:
                    Log.d(TAG, "Failed to start soft AP with a running supplicant");
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    break;
                case CMD_SET_SCAN_MODE:
                    mIsScanMode = (message.arg1 == SCAN_ONLY_MODE);
                    break;
                case CMD_SAVE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    WifiConfigStore.saveNetwork(config);
                    break;
                case CMD_FORGET_NETWORK:
                    WifiConfigStore.forgetNetwork(message.arg1);
                    break;
                default:
                    return NOT_HANDLED;
            }
            if (eventLoggingEnabled) {
                EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mNetworkInfo.setIsAvailable(false);
        }
    }

    class SupplicantStoppingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case SUP_CONNECTION_EVENT:
                    Log.e(TAG, "Supplicant connection received while stopping");
                    break;
                case SUP_DISCONNECTION_EVENT:
                    Log.d(TAG, "Supplicant connection lost");
                    WifiNative.closeSupplicantConnection();
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverStartingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case DRIVER_START_EVENT:
                    transitionTo(mDriverStartedState);
                    break;
                    /* Queue driver commands & connection events */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                case WPS_OVERLAP_EVENT:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverStartedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            mIsRunning = true;
            updateBatteryWorkSource(null);

            /**
             * Enable bluetooth coexistence scan mode when bluetooth connection is active.
             * When this mode is on, some of the low-level scan parameters used by the
             * driver are changed to reduce interference with bluetooth
             */
            WifiNative.setBluetoothCoexistenceScanModeCommand(mBluetoothConnectionActive);
            /* set country code */
            setCountryCode();
            /* set frequency band of operation */
            setFrequencyBand();
            /* initialize network state */
            setNetworkDetailedState(DetailedState.DISCONNECTED);

            if (mIsScanMode) {
                WifiNative.setScanResultHandlingCommand(SCAN_ONLY_MODE);
                WifiNative.disconnectCommand();
                transitionTo(mScanModeState);
            } else {
                WifiNative.setScanResultHandlingCommand(CONNECT_MODE);
                WifiNative.reconnectCommand();
                transitionTo(mDisconnectedState);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            boolean eventLoggingEnabled = true;
            switch(message.what) {
                case CMD_SET_SCAN_TYPE:
                    if (message.arg1 == SCAN_ACTIVE) {
                        WifiNative.setScanModeCommand(true);
                    } else {
                        WifiNative.setScanModeCommand(false);
                    }
                    break;
                case CMD_START_SCAN:
                    eventLoggingEnabled = false;
                    WifiNative.scanCommand(message.arg1 == SCAN_ACTIVE);
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    setHighPerfModeEnabledNative(message.arg1 == 1);
                    break;
                case CMD_SET_COUNTRY_CODE:
                    String country = (String) message.obj;
                    Log.d(TAG, "set country code " + country);
                    if (!WifiNative.setCountryCodeCommand(country.toUpperCase())) {
                        Log.e(TAG, "Failed to set country code " + country);
                    }
                    break;
                case CMD_SET_FREQUENCY_BAND:
                    int band =  message.arg1;
                    Log.d(TAG, "set frequency band " + band);
                    if (WifiNative.setBandCommand(band)) {
                        mFrequencyBand.set(band);
                        //Fetch the latest scan results when frequency band is set
                        startScan(true);
                    } else {
                        Log.e(TAG, "Failed to set frequency band " + band);
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    WifiNative.setBluetoothCoexistenceScanModeCommand(mBluetoothConnectionActive);
                    break;
                case CMD_STOP_DRIVER:
                    mWakeLock.acquire();
                    WifiNative.stopDriverCommand();
                    transitionTo(mDriverStoppingState);
                    mWakeLock.release();
                    break;
                case CMD_START_PACKET_FILTERING:
                    WifiNative.startPacketFiltering();
                    break;
                case CMD_STOP_PACKET_FILTERING:
                    WifiNative.stopPacketFiltering();
                    break;
                default:
                    return NOT_HANDLED;
            }
            if (eventLoggingEnabled) {
                EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            }
            return HANDLED;
        }
        @Override
        public void exit() {
            if (DBG) Log.d(TAG, getName() + "\n");
            mIsRunning = false;
            updateBatteryWorkSource(null);
            mScanResults = null;
        }
    }

    class DriverStoppingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case DRIVER_STOP_EVENT:
                    transitionTo(mDriverStoppedState);
                    break;
                    /* Queue driver commands */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverStoppedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_START_DRIVER:
                    mWakeLock.acquire();
                    WifiNative.startDriverCommand();
                    transitionTo(mDriverStartingState);
                    mWakeLock.release();
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class ScanModeState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        /* Ignore */
                        return HANDLED;
                    } else {
                        WifiNative.setScanResultHandlingCommand(message.arg1);
                        WifiNative.reconnectCommand();
                        mIsScanMode = false;
                        transitionTo(mDisconnectedState);
                    }
                    break;
                    /* Ignore */
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class ConnectModeState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            StateChangeResult stateChangeResult;
            switch(message.what) {
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                    mSupplicantStateTracker.sendMessage(PASSWORD_MAY_BE_INCORRECT_EVENT);
                    break;
                case WPS_OVERLAP_EVENT:
                    /* We just need to broadcast the error */
                    sendErrorBroadcast(WifiManager.WPS_OVERLAP_ERROR);
                    break;
                case SUPPLICANT_STATE_CHANGE_EVENT:
                    stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = stateChangeResult.state;
                    // Supplicant state change
                    // [31-13] Reserved for future use
                    // [8 - 0] Supplicant state (as defined in SupplicantState.java)
                    // 50023 supplicant_state_changed (custom|1|5)
                    EventLog.writeEvent(EVENTLOG_SUPPLICANT_STATE_CHANGED, state.ordinal());
                    mWifiInfo.setSupplicantState(state);
                    mWifiInfo.setNetworkId(stateChangeResult.networkId);
                    if (state == SupplicantState.ASSOCIATING) {
                        /* BSSID is valid only in ASSOCIATING state */
                        mWifiInfo.setBSSID(stateChangeResult.BSSID);
                    }

                    mSupplicantStateTracker.sendMessage(Message.obtain(message));
                    mWpsStateMachine.sendMessage(Message.obtain(message));
                    break;
                    /* Do a redundant disconnect without transition */
                case CMD_DISCONNECT:
                    WifiNative.disconnectCommand();
                    break;
                case CMD_RECONNECT:
                    WifiNative.reconnectCommand();
                    break;
                case CMD_REASSOCIATE:
                    WifiNative.reassociateCommand();
                    break;
                case CMD_CONNECT_NETWORK:
                    int netId = message.arg1;
                    WifiConfiguration config = (WifiConfiguration) message.obj;

                    /* We connect to a specific network by issuing a select
                     * to the WifiConfigStore. This enables the network,
                     * while disabling all other networks in the supplicant.
                     * Disabling a connected network will cause a disconnection
                     * from the network. A reconnectCommand() will then initiate
                     * a connection to the enabled network.
                     */
                    if (config != null) {
                        WifiConfigStore.selectNetwork(config);
                    } else {
                        WifiConfigStore.selectNetwork(netId);
                    }

                    /* The state tracker handles enabling networks upon completion/failure */
                    mSupplicantStateTracker.sendMessage(CMD_CONNECT_NETWORK);

                    WifiNative.reconnectCommand();

                    /* Expect a disconnection from the old connection */
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_START_WPS:
                    mWpsStateMachine.sendMessage(Message.obtain(message));
                    transitionTo(mWaitForWpsCompletionState);
                    break;
                case SCAN_RESULTS_EVENT:
                    /* Set the scan setting back to "connect" mode */
                    WifiNative.setScanResultHandlingCommand(CONNECT_MODE);
                    /* Handle scan results */
                    return NOT_HANDLED;
                case NETWORK_CONNECTION_EVENT:
                    Log.d(TAG,"Network connection established");
                    mLastNetworkId = message.arg1;
                    mLastBssid = (String) message.obj;

                    //TODO: make supplicant modification to push this in events
                    mWifiInfo.setSSID(fetchSSID());
                    mWifiInfo.setBSSID(mLastBssid);
                    mWifiInfo.setNetworkId(mLastNetworkId);
                    /* send event to CM & network change broadcast */
                    setNetworkDetailedState(DetailedState.OBTAINING_IPADDR);
                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mConnectingState);
                    break;
                case NETWORK_DISCONNECTION_EVENT:
                    Log.d(TAG,"Network connection lost");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class ConnectingState extends HierarchicalState {
        boolean mModifiedBluetoothCoexistenceMode;
        int mPowerMode;
        boolean mUseStaticIp;
        Thread mDhcpThread;

        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            mUseStaticIp = WifiConfigStore.isUsingStaticIp(mLastNetworkId);
            if (!mUseStaticIp) {
                mDhcpThread = null;
                mModifiedBluetoothCoexistenceMode = false;
                mPowerMode = POWER_MODE_AUTO;

                if (!mBluetoothConnectionActive) {
                    /*
                     * There are problems setting the Wi-Fi driver's power
                     * mode to active when bluetooth coexistence mode is
                     * enabled or sense.
                     * <p>
                     * We set Wi-Fi to active mode when
                     * obtaining an IP address because we've found
                     * compatibility issues with some routers with low power
                     * mode.
                     * <p>
                     * In order for this active power mode to properly be set,
                     * we disable coexistence mode until we're done with
                     * obtaining an IP address.  One exception is if we
                     * are currently connected to a headset, since disabling
                     * coexistence would interrupt that connection.
                     */
                    mModifiedBluetoothCoexistenceMode = true;

                    // Disable the coexistence mode
                    WifiNative.setBluetoothCoexistenceModeCommand(
                            WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
                }

                mPowerMode =  WifiNative.getPowerModeCommand();
                if (mPowerMode < 0) {
                  // Handle the case where supplicant driver does not support
                  // getPowerModeCommand.
                    mPowerMode = POWER_MODE_AUTO;
                }
                if (mPowerMode != POWER_MODE_ACTIVE) {
                    WifiNative.setPowerModeCommand(POWER_MODE_ACTIVE);
                }

                Log.d(TAG, "DHCP request started");
                mDhcpThread = new Thread(new Runnable() {
                    public void run() {
                        DhcpInfo dhcpInfo = new DhcpInfo();
                        if (NetworkUtils.runDhcp(mInterfaceName, dhcpInfo)) {
                            Log.d(TAG, "DHCP request succeeded");
                            synchronized (mDhcpInfo) {
                                mDhcpInfo = dhcpInfo;
                            }
                            sendMessage(CMD_IP_CONFIG_SUCCESS);
                        } else {
                            Log.d(TAG, "DHCP request failed: " +
                                    NetworkUtils.getDhcpError());
                            sendMessage(CMD_IP_CONFIG_FAILURE);
                        }
                    }
                });
                mDhcpThread.start();
            } else {
                DhcpInfo dhcpInfo = WifiConfigStore.getIpConfiguration(mLastNetworkId);
                if (NetworkUtils.configureInterface(mInterfaceName, dhcpInfo)) {
                    Log.v(TAG, "Static IP configuration succeeded");
                    synchronized (mDhcpInfo) {
                        mDhcpInfo = dhcpInfo;
                    }
                    sendMessage(CMD_IP_CONFIG_SUCCESS);
                } else {
                    Log.v(TAG, "Static IP configuration failed");
                    sendMessage(CMD_IP_CONFIG_FAILURE);
                }
            }
         }
      @Override
      public boolean processMessage(Message message) {
          if (DBG) Log.d(TAG, getName() + message.toString() + "\n");

          switch(message.what) {
              case CMD_IP_CONFIG_SUCCESS:
                  mLastSignalLevel = -1; // force update of signal strength
                  synchronized (mDhcpInfo) {
                      mWifiInfo.setIpAddress(mDhcpInfo.ipAddress);
                  }
                  configureLinkProperties();
                  if (getNetworkDetailedState() == DetailedState.CONNECTED) {
                      sendLinkConfigurationChangedBroadcast();
                  } else {
                      setNetworkDetailedState(DetailedState.CONNECTED);
                      sendNetworkStateChangeBroadcast(mLastBssid);
                  }
                  //TODO: The framework is not detecting a DHCP renewal and a possible
                  //IP change. we should detect this and send out a config change broadcast
                  transitionTo(mConnectedState);
                  break;
              case CMD_IP_CONFIG_FAILURE:
                  mWifiInfo.setIpAddress(0);

                  Log.e(TAG, "IP configuration failed");
                  /**
                   * If we've exceeded the maximum number of retries for DHCP
                   * to a given network, disable the network
                   */
                  if (++mReconnectCount > getMaxDhcpRetries()) {
                      Log.e(TAG, "Failed " +
                              mReconnectCount + " times, Disabling " + mLastNetworkId);
                      WifiConfigStore.disableNetwork(mLastNetworkId);
                      mReconnectCount = 0;
                  }

                  /* DHCP times out after about 30 seconds, we do a
                   * disconnect and an immediate reconnect to try again
                   */
                  WifiNative.disconnectCommand();
                  WifiNative.reconnectCommand();
                  transitionTo(mDisconnectingState);
                  break;
              case CMD_DISCONNECT:
                  WifiNative.disconnectCommand();
                  transitionTo(mDisconnectingState);
                  break;
                  /* Ignore connection to same network */
              case CMD_CONNECT_NETWORK:
                  int netId = message.arg1;
                  if (mWifiInfo.getNetworkId() == netId) {
                      break;
                  }
                  return NOT_HANDLED;
              case CMD_SAVE_NETWORK:
                  deferMessage(message);
                  break;
                  /* Ignore */
              case NETWORK_CONNECTION_EVENT:
                  break;
              case CMD_STOP_DRIVER:
                  sendMessage(CMD_DISCONNECT);
                  deferMessage(message);
                  break;
              case CMD_SET_SCAN_MODE:
                  if (message.arg1 == SCAN_ONLY_MODE) {
                      sendMessage(CMD_DISCONNECT);
                      deferMessage(message);
                  }
                  break;
                  /* Defer scan when IP is being fetched */
              case CMD_START_SCAN:
                  deferMessage(message);
                  break;
                  /* Defer any power mode changes since we must keep active power mode at DHCP */
              case CMD_SET_HIGH_PERF_MODE:
                  deferMessage(message);
                  break;
              default:
                return NOT_HANDLED;
          }
          EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
          return HANDLED;
      }

      @Override
      public void exit() {
          /* reset power state & bluetooth coexistence if on DHCP */
          if (!mUseStaticIp) {
              if (mPowerMode != POWER_MODE_ACTIVE) {
                  WifiNative.setPowerModeCommand(mPowerMode);
              }

              if (mModifiedBluetoothCoexistenceMode) {
                  // Set the coexistence mode back to its default value
                  WifiNative.setBluetoothCoexistenceModeCommand(
                          WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
              }
          }

      }
    }

    class ConnectedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            mRssiPollToken++;
            if (mEnableRssiPolling) {
                sendMessage(obtainMessage(WifiStateMachine.CMD_RSSI_POLL, mRssiPollToken, 0));
            }
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            boolean eventLoggingEnabled = true;
            switch (message.what) {
                case CMD_DISCONNECT:
                    WifiNative.disconnectCommand();
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_STOP_DRIVER:
                    sendMessage(CMD_DISCONNECT);
                    deferMessage(message);
                    break;
                case CMD_REQUEST_CM_WAKELOCK:
                    if (mCm == null) {
                        mCm = (ConnectivityManager)mContext.getSystemService(
                                Context.CONNECTIVITY_SERVICE);
                    }
                    mCm.requestNetworkTransitionWakelock(TAG);
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        sendMessage(CMD_DISCONNECT);
                        deferMessage(message);
                    }
                    break;
                case CMD_START_SCAN:
                    eventLoggingEnabled = false;
                    /* When the network is connected, re-scanning can trigger
                     * a reconnection. Put it in scan-only mode during scan.
                     * When scan results are received, the mode is switched
                     * back to CONNECT_MODE.
                     */
                    WifiNative.setScanResultHandlingCommand(SCAN_ONLY_MODE);
                    WifiNative.scanCommand(message.arg1 == SCAN_ACTIVE);
                    break;
                    /* Ignore connection to same network */
                case CMD_CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        break;
                    }
                    return NOT_HANDLED;
                case CMD_SAVE_NETWORK:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    NetworkUpdateResult result = WifiConfigStore.saveNetwork(config);
                    if (mWifiInfo.getNetworkId() == result.getNetworkId()) {
                        if (result.hasIpChanged()) {
                            Log.d(TAG,"Reconfiguring IP on connection");
                            NetworkUtils.resetConnections(mInterfaceName);
                            transitionTo(mConnectingState);
                        }
                        if (result.hasProxyChanged()) {
                            Log.d(TAG,"Reconfiguring proxy on connection");
                            configureLinkProperties();
                            sendLinkConfigurationChangedBroadcast();
                        }
                    }
                    break;
                    /* Ignore */
                case NETWORK_CONNECTION_EVENT:
                    break;
                case CMD_RSSI_POLL:
                    eventLoggingEnabled = false;
                    if (message.arg1 == mRssiPollToken) {
                        // Get Info and continue polling
                        fetchRssiAndLinkSpeedNative();
                        sendMessageDelayed(obtainMessage(WifiStateMachine.CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    } else {
                        // Polling has completed
                    }
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    mRssiPollToken++;
                    if (mEnableRssiPolling) {
                        // first poll
                        fetchRssiAndLinkSpeedNative();
                        sendMessageDelayed(obtainMessage(WifiStateMachine.CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            if (eventLoggingEnabled) {
                EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            }
            return HANDLED;
        }
    }

    class DisconnectingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_STOP_DRIVER: /* Stop driver only after disconnect handled */
                    deferMessage(message);
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        deferMessage(message);
                    }
                    break;
                    /* Handle in  DisconnectedState */
                case SUPPLICANT_STATE_CHANGE_EVENT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DisconnectedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            /**
             * In a disconnected state, an infrequent scan that wakes
             * up the device is needed to ensure a user connects to
             * an access point on the move
             */
            long scanMs = Settings.Secure.getLong(mContext.getContentResolver(),
                    Settings.Secure.WIFI_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS);

            mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + scanMs, scanMs, mScanIntent);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        WifiNative.setScanResultHandlingCommand(message.arg1);
                        //Supplicant disconnect to prevent further connects
                        WifiNative.disconnectCommand();
                        mIsScanMode = true;
                        transitionTo(mScanModeState);
                    }
                    break;
                    /* Ignore network disconnect */
                case NETWORK_DISCONNECTION_EVENT:
                    break;
                case SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    setNetworkDetailedState(WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    /* DriverStartedState does the rest of the handling */
                    return NOT_HANDLED;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }

        @Override
        public void exit() {
            mAlarmManager.cancel(mScanIntent);
        }
    }

    class WaitForWpsCompletionState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                /* Defer all commands that can cause connections to a different network
                 * or put the state machine out of connect mode
                 */
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_CONNECT_NETWORK:
                case CMD_ENABLE_NETWORK:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case NETWORK_CONNECTION_EVENT: /* Handled after IP & proxy update */
                    deferMessage(message);
                    break;
                case NETWORK_DISCONNECTION_EVENT:
                    Log.d(TAG,"Network connection lost");
                    handleNetworkDisconnect();
                    break;
                case WPS_COMPLETED_EVENT:
                    /* we are still disconnected until we see a network connection
                     * notification */
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class SoftApStartedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_STOP_AP:
                    Log.d(TAG,"Stopping Soft AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING);
                    try {
                        nwService.stopAccessPoint();
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in stopAccessPoint()");
                    }
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_START_AP:
                    Log.d(TAG,"SoftAP set on a running access point");
                    try {
                        nwService.setAccessPoint((WifiConfiguration) message.obj,
                                    mInterfaceName,
                                    SOFTAP_IFACE);
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in nwService during soft AP set");
                        try {
                            nwService.stopAccessPoint();
                        } catch (Exception ee) {
                            Slog.e(TAG, "Could not stop AP, :" + ee);
                        }
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_FAILED, 0));
                    }
                    break;
                /* Fail client mode operation when soft AP is enabled */
                case CMD_START_SUPPLICANT:
                    Log.e(TAG,"Cannot start supplicant with a running soft AP");
                    setWifiState(WIFI_STATE_UNKNOWN);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }
}
