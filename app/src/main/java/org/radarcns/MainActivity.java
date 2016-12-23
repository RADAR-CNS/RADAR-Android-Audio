package org.radarcns;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.radarcns.android.DeviceServiceConnection;
import org.radarcns.android.DeviceState;
import org.radarcns.android.DeviceStatusListener;
import org.radarcns.empaticaE4.E4DeviceStatus;
import org.radarcns.empaticaE4.E4HeartbeatToast;
import org.radarcns.empaticaE4.E4Service;
import org.radarcns.kafka.rest.ServerStatusListener;
import org.radarcns.pebble2.Pebble2DeviceStatus;
import org.radarcns.pebble2.Pebble2HeartbeatToast;
import org.radarcns.pebble2.Pebble2Service;
import org.radarcns.phoneSensors.PhoneSensorsDeviceStatus;
import org.radarcns.phoneSensors.PhoneSensorsService;
import org.radarcns.util.Boast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static org.radarcns.RadarConfiguration.CALL_LOG_UPDATE_RATE_KEY;
import static org.radarcns.RadarConfiguration.CONDENSED_DISPLAY_KEY;
import static org.radarcns.RadarConfiguration.DEVICE_GROUP_ID_KEY;
import static org.radarcns.RadarConfiguration.EMPATICA_API_KEY;
import static org.radarcns.RadarConfiguration.KAFKA_CLEAN_RATE_KEY;
import static org.radarcns.RadarConfiguration.KAFKA_RECORDS_SEND_LIMIT_KEY;
import static org.radarcns.RadarConfiguration.KAFKA_REST_PROXY_URL_KEY;
import static org.radarcns.RadarConfiguration.KAFKA_UPLOAD_RATE_KEY;
import static org.radarcns.RadarConfiguration.SCHEMA_REGISTRY_URL_KEY;
import static org.radarcns.RadarConfiguration.SENDER_CONNECTION_TIMEOUT_KEY;
import static org.radarcns.RadarConfiguration.UI_REFRESH_RATE_KEY;
import static org.radarcns.android.DeviceService.SERVER_RECORDS_SENT_NUMBER;
import static org.radarcns.android.DeviceService.SERVER_RECORDS_SENT_TOPIC;
import static org.radarcns.empaticaE4.E4Service.DEVICE_CONNECT_FAILED;
import static org.radarcns.empaticaE4.E4Service.DEVICE_STATUS_NAME;
import static org.radarcns.empaticaE4.E4Service.SERVER_STATUS_CHANGED;

public class MainActivity extends AppCompatActivity {
    private final static Logger logger = LoggerFactory.getLogger(MainActivity.class);

    private static final int REQUEST_ENABLE_PERMISSIONS = 2;
    private static final int MAX_UI_DEVICE_NAME_LENGTH = 25;

    private long uiRefreshRate;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private Runnable mUIScheduler;
    private DeviceUIUpdater mUIUpdater;
    private boolean isForcedDisconnected;
    private final boolean[] mConnectionIsBound;

    /** Defines callbacks for service binding, passed to bindService() */
    private final DeviceServiceConnection<E4DeviceStatus> mE4Connection;
    private final DeviceServiceConnection<Pebble2DeviceStatus> pebble2Connection;
    private final DeviceServiceConnection<PhoneSensorsDeviceStatus> phoneConnection;
    private final BroadcastReceiver serverStatusListener;
    private final BroadcastReceiver bluetoothReceiver;
    private final BroadcastReceiver deviceFailedReceiver;

    /** Connections. 0 = Empatica, 1 = Angel sensor, 2 = Pebble sensor **/
    private DeviceServiceConnection[] mConnections;

    /** Overview UI **/
    private TextView[] mDeviceNameLabels;
    private View[] mStatusIcons;
    private TextView[] mTemperatureLabels;
    private TextView[] mHeartRateLabels;
    private TextView[] mAccelerationLabels;
    private TextView[] mRecordsSentLabels;
    private ImageView[] mBatteryLabels;
    private Button[] mDeviceInputButtons;
    private String[] mInputDeviceKeys = new String[4];
    private int[] mTotalRecordsSent = new int[4];
    private Long[] mLastRecordsSentTimeMillis = new Long[4];

    private View mServerStatusIcon;
    private TextView mServerMessage;
    private View mFirebaseStatusIcon;
    private TextView mFirebaseMessage;

    final static DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public RadarConfiguration radarConfiguration;

    private final Runnable bindServicesRunner = new Runnable() {
        @Override
        public void run() {
            if (!mConnectionIsBound[0]) {
                Intent e4serviceIntent = new Intent(MainActivity.this, E4Service.class);
                Bundle extras = new Bundle();
                configureEmpatica(extras);
                e4serviceIntent.putExtras(extras);

                mE4Connection.bind(e4serviceIntent);
                mConnectionIsBound[0] = true;
            }
            if (!mConnectionIsBound[2]) {
                Intent pebble2Intent = new Intent(MainActivity.this, Pebble2Service.class);
                Bundle extras = new Bundle();
                configurePebble2(extras);
                pebble2Intent.putExtras(extras);

                pebble2Connection.bind(pebble2Intent);
                mConnectionIsBound[2] = true;
            }
            if (!mConnectionIsBound[3]) {
                Intent phoneIntent = new Intent(MainActivity.this, PhoneSensorsService.class);
                Bundle extras = new Bundle();
                configurePhoneSensors(extras);
                phoneIntent.putExtras(extras);

                phoneConnection.bind(phoneIntent);
                mConnectionIsBound[3] = true;
            }
        }

    };

    private void configureEmpatica(Bundle bundle) {
        configureServiceExtras(bundle);
        radarConfiguration.putExtras(bundle, EMPATICA_API_KEY);
    }

    private void configurePebble2(Bundle bundle) {
        configureServiceExtras(bundle);
    }

    private void configurePhoneSensors(Bundle bundle) {
        configureServiceExtras(bundle);
    }

    private void configureServiceExtras(Bundle bundle) {
        // Add the default configuration parameters given to the service intents
        radarConfiguration.putExtras(bundle,
                KAFKA_REST_PROXY_URL_KEY, SCHEMA_REGISTRY_URL_KEY, DEVICE_GROUP_ID_KEY,
                KAFKA_UPLOAD_RATE_KEY, KAFKA_CLEAN_RATE_KEY, KAFKA_RECORDS_SEND_LIMIT_KEY,
                SENDER_CONNECTION_TIMEOUT_KEY, CALL_LOG_UPDATE_RATE_KEY );
    }

    public MainActivity() {
        super();
        isForcedDisconnected = false;
        mE4Connection = new DeviceServiceConnection<>(this, E4DeviceStatus.CREATOR, E4Service.class.getName());
        pebble2Connection = new DeviceServiceConnection<>(this, Pebble2DeviceStatus.CREATOR, Pebble2Service.class.getName());
        phoneConnection = new DeviceServiceConnection<>(this, PhoneSensorsDeviceStatus.CREATOR, PhoneSensorsService.class.getName());
        mConnections = new DeviceServiceConnection[] {mE4Connection, null, pebble2Connection, phoneConnection};
        mConnectionIsBound = new boolean[] {false, false, false, false};

        serverStatusListener = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(SERVER_STATUS_CHANGED)) {
                    final ServerStatusListener.Status status = ServerStatusListener.Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, 0)];
                    updateServerStatus(status);
                } else if (intent.getAction().equals(SERVER_RECORDS_SENT_TOPIC)) {
                    String triggerKey = intent.getStringExtra(SERVER_RECORDS_SENT_TOPIC); // topicName that updated
                    int numberOfRecordsSent = intent.getIntExtra(SERVER_RECORDS_SENT_NUMBER, 0);
                    updateServerRecordsSent(triggerKey, numberOfRecordsSent);
                }
            }
        };

        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    logger.info("Bluetooth state {}", state);
                    // Upon state change, restart ui handler and restart Scanning.
                    if (state == BluetoothAdapter.STATE_ON) {
                        logger.info("Bluetooth has turned on");
                        getHandler().postDelayed(mUIScheduler, uiRefreshRate);
                        startScanning();
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        logger.warn("Bluetooth is off");
                        getHandler().postDelayed(mUIScheduler, uiRefreshRate);
                        startScanning();
                    }
                }
            }
        };

        deviceFailedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, final Intent intent) {
                if (intent.getAction().equals(DEVICE_CONNECT_FAILED)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Boast.makeText(MainActivity.this, "Cannot connect to device " + intent.getStringExtra(DEVICE_STATUS_NAME), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overview);
        initializeViews();
        initializeRemoteConfig();

        // Start the UI thread
        uiRefreshRate = radarConfiguration.getLong(UI_REFRESH_RATE_KEY);
        mUIUpdater = new DeviceUIUpdater();
        mUIScheduler = new Runnable() {
            @Override
            public void run() {
                try {
                    // Update all rows in the UI with the data from the connections
                    mUIUpdater.update();
                } catch (RemoteException e) {
                    logger.warn("Failed to update device data", e);
                } finally {
                    getHandler().postDelayed(mUIScheduler, uiRefreshRate);
                }
            }
        };

        // Not needed in API level 22.
        // checkBluetoothPermissions();

        // Check availability of Google Play Services
        if ( GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS ) {
            mFirebaseStatusIcon.setBackgroundResource(R.drawable.status_disconnected);
            mFirebaseMessage.setText(R.string.playServicesUnavailable);
        }
    }

    private void initializeViews() {
        // The columns, fixed to four rows.
        mDeviceNameLabels = new TextView[] {
                (TextView) findViewById(R.id.deviceNameRow1),
                (TextView) findViewById(R.id.deviceNameRow2),
                (TextView) findViewById(R.id.deviceNameRow3),
                (TextView) findViewById(R.id.deviceNameRow4)
        };

        mStatusIcons = new View[] {
                findViewById(R.id.statusRow1),
                findViewById(R.id.statusRow2),
                findViewById(R.id.statusRow3),
                findViewById(R.id.statusRow4)
        };

        mTemperatureLabels = new TextView[] {
                (TextView) findViewById(R.id.temperatureRow1),
                (TextView) findViewById(R.id.temperatureRow2),
                (TextView) findViewById(R.id.temperatureRow3),
                (TextView) findViewById(R.id.temperatureRow4)
        };

        mHeartRateLabels = new TextView[] {
                (TextView) findViewById(R.id.heartRateRow1),
                (TextView) findViewById(R.id.heartRateRow2),
                (TextView) findViewById(R.id.heartRateRow3),
                (TextView) findViewById(R.id.heartRateRow4)
        };

        mAccelerationLabels = new TextView[] {
                (TextView) findViewById(R.id.accelerationRow1),
                (TextView) findViewById(R.id.accelerationRow2),
                (TextView) findViewById(R.id.accelerationRow3),
                (TextView) findViewById(R.id.accelerationRow4)
        };

        mBatteryLabels = new ImageView[] {
                (ImageView) findViewById(R.id.batteryRow1),
                (ImageView) findViewById(R.id.batteryRow2),
                (ImageView) findViewById(R.id.batteryRow3),
                (ImageView) findViewById(R.id.batteryRow4)
        };

        mDeviceInputButtons = new Button[] {
                (Button) findViewById(R.id.inputDeviceNameButtonRow1),
                (Button) findViewById(R.id.inputDeviceNameButtonRow2),
                (Button) findViewById(R.id.inputDeviceNameButtonRow3),
                (Button) findViewById(R.id.inputDeviceNameButtonRow4)
        };

        mRecordsSentLabels = new TextView[] {
                (TextView) findViewById(R.id.recordsSentRow1),
                (TextView) findViewById(R.id.recordsSentRow2),
                (TextView) findViewById(R.id.recordsSentRow3),
                (TextView) findViewById(R.id.recordsSentRow4)
        };

        // Server
        mServerStatusIcon = findViewById(R.id.statusServer);
        mServerMessage = (TextView) findViewById( R.id.statusServerMessage);

        // Firebase
        mFirebaseStatusIcon = findViewById(R.id.firebaseStatus);
        mFirebaseMessage = (TextView) findViewById( R.id.firebaseStatusMessage);
    }

    private void initializeRemoteConfig() {
        // TODO: disable developer mode in production
        radarConfiguration = RadarConfiguration.configure(true, R.xml.remote_config_defaults);
        radarConfiguration.onFetchComplete(this, new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                if (task.isSuccessful()) {
                    // Once the config is successfully fetched it must be
                    // activated before newly fetched values are returned.
                    radarConfiguration.activateFetched();
                    if (mConnectionIsBound[0]) {
                        Bundle bundle = new Bundle();
                        configureEmpatica(bundle);
                        mE4Connection.updateConfiguration(bundle);
                    }
                    if (mConnectionIsBound[2]) {
                        Bundle bundle = new Bundle();
                        configurePebble2(bundle);
                        pebble2Connection.updateConfiguration(bundle);
                    }
                    if (mConnectionIsBound[3]) {
                        Bundle bundle = new Bundle();
                        configurePhoneSensors(bundle);
                        phoneConnection.updateConfiguration(bundle);
                    }
                    logger.info("Remote Config: Activate success.");
                    // Set global properties.
                    mFirebaseStatusIcon.setBackgroundResource(R.drawable.status_connected);
                    mFirebaseMessage.setText("Remote config fetched from the server ("
                            + timeFormat.format( System.currentTimeMillis() ) + ")");
                } else {
                    Toast.makeText(MainActivity.this, "Remote Config: Fetch Failed",
                            Toast.LENGTH_SHORT).show();
                    logger.info("Remote Config: Fetch failed. Stacktrace: {}", task.getException());
                }
            }
        });
    }

    @Override
    protected void onResume() {
        logger.info("mainActivity onResume");
        super.onResume();
        mHandler.postDelayed(bindServicesRunner, 300L);

        radarConfiguration.fetch();
    }

    @Override
    protected void onPause() {
        logger.info("mainActivity onPause");
        super.onPause();
        mHandler.removeCallbacks(mUIScheduler);
    }

    @Override
    protected void onStart() {
        logger.info("mainActivity onStart");
        super.onStart();
        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(serverStatusListener, new IntentFilter(E4Service.SERVER_STATUS_CHANGED));
        registerReceiver(serverStatusListener, new IntentFilter(E4Service.SERVER_RECORDS_SENT_TOPIC));
        registerReceiver(deviceFailedReceiver, new IntentFilter(E4Service.DEVICE_CONNECT_FAILED));

        mHandlerThread = new HandlerThread("E4Service connection", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        synchronized (this) {
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        mHandler.post(mUIScheduler);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mConnections.length; i++) {
                    mConnectionIsBound[i] = false;
                }
            }
        });
    }

    @Override
    protected void onStop() {
        logger.info("mainActivity onStop");
        super.onStop();
        unregisterReceiver(serverStatusListener);
        unregisterReceiver(deviceFailedReceiver);
        unregisterReceiver(bluetoothReceiver);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mConnections.length; i++) {
                    if (mConnectionIsBound[i]) {
                        mConnectionIsBound[i] = false;
                        mConnections[i].unbind();
                    }
                }
            }
        });
        mHandlerThread.quitSafely();
    }

    private synchronized Handler getHandler() {
        return mHandler;
    }

    private void disconnect() {
        for (int i = 0; i < mConnections.length; i++) {
            disconnect(i);
        }
    }

    private void disconnect(int row) {
        DeviceServiceConnection connection = mConnections[row];
        if (connection != null && connection.isRecording()) {
            try {
                connection.stopRecording();
            } catch (RemoteException e) {
                // it cannot be reached so it already stopped recording
            }
        }
    }

    /**
     * If no E4Service is scanning, and ask one to start scanning.
     */
    private void startScanning() {
        if (isForcedDisconnected) {
            return;
        } else if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            enableBt();
            return;
        }
        for (int i = 0; i < mConnections.length; i++) {
            DeviceServiceConnection connection = mConnections[i];
            if (connection == null || !connection.hasService() || connection.isRecording()) {
                continue;
            }
            Set<String> acceptableIds;
            if (mInputDeviceKeys[i] != null && !mInputDeviceKeys[i].isEmpty()) {
                acceptableIds = Collections.singleton(mInputDeviceKeys[i]);
            } else {
                acceptableIds = Collections.emptySet();
            }
            try {
                logger.info("Starting recording on connection {}", i);
                connection.startRecording(acceptableIds);
            } catch (RemoteException e) {
                logger.error("Failed to start recording for device {}", i, e);
            }
        }
    }

    public void serviceConnected(final DeviceServiceConnection connection) {
        try {
            ServerStatusListener.Status status = connection.getServerStatus();
            logger.info("Initial server status: {}", status);
            updateServerStatus(status);
        } catch (RemoteException e) {
            logger.warn("Failed to update UI server status");
        }
        startScanning();
    }

    public synchronized void serviceDisconnected(final DeviceServiceConnection connection) {
        mHandler.post(bindServicesRunner);
    }

    public void deviceStatusUpdated(final DeviceServiceConnection connection, final DeviceStatusListener.Status status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Boast.makeText(MainActivity.this, status.toString(), Toast.LENGTH_SHORT).show();
                switch (status) {
                    case CONNECTED:
                        break;
                    case CONNECTING:
//                        statusLabel.setText("CONNECTING");
                        logger.info( "Device name is {} while connecting.", connection.getDeviceName() );
                        for (int i = 0; i < mConnections.length; i++) {
                            if (mConnections[i] != connection) {
                                continue;
                            }
                            // Reject if device name inputted does not equal device nameA
                            if (mInputDeviceKeys[i] != null && !connection.isAllowedDevice(mInputDeviceKeys[i])) {
                                logger.info("Device name '{}' is not equal to '{}'", connection.getDeviceName(), mInputDeviceKeys[i]);
                                Boast.makeText(MainActivity.this, String.format("Device '%s' rejected", connection.getDeviceName()), Toast.LENGTH_LONG).show();
                                disconnect();
                            }
                        }
                        break;
                    case DISCONNECTED:
                        startScanning();
                        break;
                    case READY:
                        break;
                }
            }
        });
    }

    void enableBt() {
        BluetoothAdapter btAdaptor = BluetoothAdapter.getDefaultAdapter();
        if (!btAdaptor.isEnabled() && btAdaptor.getState() != BluetoothAdapter.STATE_TURNING_ON) {
            Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            btIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(btIntent);
        }
    }

    private void checkBluetoothPermissions() {
        String[] permissions = {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN};

        boolean waitingForPermission = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                waitingForPermission = true;
                break;
            }
        }
        if (waitingForPermission) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_ENABLE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ENABLE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted.
                startScanning();
            } else {
                // User refused to grant permission.
                Boast.makeText(this, "Cannot connect to Empatica E4DeviceManager without location permissions", Toast.LENGTH_LONG).show();
            }
        }
    }

    public class DeviceUIUpdater implements Runnable {
        /** Data formats **/
        final DecimalFormat singleDecimal = new DecimalFormat("0.0");
        final DecimalFormat doubleDecimal = new DecimalFormat("0.00");
        final DecimalFormat noDecimals = new DecimalFormat("0");
        final DeviceState[] deviceData;
        final String[] deviceNames;

        DeviceUIUpdater() {
            deviceData = new DeviceState[mConnections.length];
            deviceNames = new String[mConnections.length];
        }

        public void update() throws RemoteException {
            for (int i = 0; i < mConnections.length; i++) {
                if (mConnections[i] != null && mConnections[i].hasService()) {
                    deviceData[i] = mConnections[i].getDeviceData();
                    switch (deviceData[i].getStatus()) {
                        case CONNECTED:
                        case CONNECTING:
                            deviceNames[i] = mConnections[i].getDeviceName();
                            break;
                        default:
                            deviceNames[i] = null;
                            break;
                    }
                } else {
                    deviceData[i] = null;
                    deviceNames[i] = null;
                }
            }
            runOnUiThread(this);
        }

        @Override
        public void run() {
            for (int i = 0; i < mConnections.length; i++) {
                // Update all fields
                updateDeviceStatus(deviceData[i], i);
                updateTemperature(deviceData[i], i);
                updateHeartRate(deviceData[i], i);
                updateAcceleration(deviceData[i], i);
                updateBattery(deviceData[i], i);
                updateDeviceName(deviceNames[i], i);
                updateDeviceTotalRecordsSent(i);
            }
        }

        public void updateDeviceStatus(DeviceState deviceData, int row ) {
            // Connection status. Change icon used.
            switch (deviceData == null ? DeviceStatusListener.Status.DISCONNECTED : deviceData.getStatus()) {
                case CONNECTED:
                    mStatusIcons[row].setBackgroundResource( R.drawable.status_connected );
                    break;
                case DISCONNECTED:
                    mStatusIcons[row].setBackgroundResource( R.drawable.status_disconnected );
                    break;
                case READY:
                case CONNECTING:
                    mStatusIcons[row].setBackgroundResource( R.drawable.status_searching );
                    break;
                default:
                    mStatusIcons[row].setBackgroundResource( R.drawable.status_searching );
            }
        }

        public void updateTemperature(DeviceState deviceData, int row ) {
            // \u2103 == ℃
            setText(mTemperatureLabels[row], deviceData == null ? Float.NaN : deviceData.getTemperature(), "\u2103", singleDecimal);
        }

        public void updateHeartRate(DeviceState deviceData, int row ) {
            setText(mHeartRateLabels[row], deviceData == null ? Float.NaN : deviceData.getHeartRate(), "bpm", noDecimals);
        }

        public void updateAcceleration(DeviceState deviceData, int row ) {
            setText(mAccelerationLabels[row], deviceData == null ? Float.NaN : deviceData.getAccelerationMagnitude(), "g", doubleDecimal);
        }

        public void updateBattery(DeviceState deviceData, int row ) {
            // Battery levels observed for E4 are 0.01, 0.1, 0.45 or 1
            Float batteryLevel = deviceData == null ? Float.NaN : deviceData.getBatteryLevel();
//            if ( row == 0 ) {logger.info("Battery: {}", batteryLevel);}

            if ( batteryLevel.isNaN() ) {
                mBatteryLabels[row].setImageResource( R.drawable.ic_battery_unknown );
            // up to 100%
            } else if ( batteryLevel > 0.5 ) {
                mBatteryLabels[row].setImageResource( R.drawable.ic_battery_full );
            // up to 45%
            } else if ( batteryLevel > 0.2 ) {
                mBatteryLabels[row].setImageResource( R.drawable.ic_battery_50 );
            // up to 10%
            } else if ( batteryLevel > 0.1 ) {
                mBatteryLabels[row].setImageResource( R.drawable.ic_battery_low );
            // up to 5% [what are possible values below 10%?]
            } else {
                mBatteryLabels[row].setImageResource( R.drawable.ic_battery_empty );
            }
        }

        public void updateDeviceName(String deviceName, int row) {
            // Restrict length of name that is shown.
            if (deviceName != null && deviceName.length() > MAX_UI_DEVICE_NAME_LENGTH - 3) {
                deviceName = deviceName.substring(0, MAX_UI_DEVICE_NAME_LENGTH) + "...";
            }

            // \u2014 == —
            mDeviceNameLabels[row].setText(deviceName == null ? "\u2014" : deviceName);
        }

        public void updateDeviceTotalRecordsSent(int row) {
            if (mLastRecordsSentTimeMillis[row] == null) {
                mRecordsSentLabels[row].setText( R.string.emptyText );
            } else {
                String message;
                Long timeSinceLastUpdate = ( System.currentTimeMillis() - mLastRecordsSentTimeMillis[row] )/1000;
                // Small test for Firebase Remote config.
                if (radarConfiguration.getBoolean(CONDENSED_DISPLAY_KEY, true)) {
                    message = String.format(Locale.US, "%1$4dk (%2$d)", mTotalRecordsSent[row]/1000, timeSinceLastUpdate);
                } else {
                    message = String.format(Locale.US, "%1$4d (updated %2$d sec. ago)", mTotalRecordsSent[row], timeSinceLastUpdate);
                }
                mRecordsSentLabels[row].setText( message );
            }

        }

        private void setText(TextView label, float value, String suffix, DecimalFormat formatter) {
            if (Float.isNaN(value)) {
                // Only overwrite default value if enabled.
                if (label.isEnabled()) {
                    // em dash
                    label.setText("\u2014");
                }
            } else {
                label.setText(formatter.format(value) + " " + suffix);
            }
        }

    }

    public void reconnectDevice(View v) {
        try {
            int rowIndex = getRowIndexFromView(v);
            // will restart scanning after disconnect
            disconnect(rowIndex);
        } catch (IndexOutOfBoundsException iobe) {
            Boast.makeText(this, "Could not restart scanning, there is no valid row index associated with this button.", Toast.LENGTH_LONG).show();
            logger.warn(iobe.getMessage());
        }
    }

    public void showDetails(final View v) {
        final int row;
        try {
            row = getRowIndexFromView(v);
        } catch (IndexOutOfBoundsException iobe) {
            logger.warn(iobe.getMessage());
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mUIUpdater.update();
                    DeviceServiceConnection connection = mConnections[row];
                    if (connection == mE4Connection) {
                        new E4HeartbeatToast(MainActivity.this).execute(connection);
                    } else if (connection == pebble2Connection) {
                        new Pebble2HeartbeatToast(MainActivity.this).execute(connection);
                    }
                } catch (RemoteException e) {
                    logger.warn("Failed to update view with device data");
                }
            }
        });

        if (radarConfiguration.isInDevelopmentMode()) {
            radarConfiguration.fetch();
        }
    }

    private int getRowIndexFromView(View v) throws IndexOutOfBoundsException {
        // Assume all elements are direct descendants from the TableRow
        View parent = (View) v.getParent();
        switch ( parent.getId() ) {

            case R.id.row1:
                return 0;

            case R.id.row2:
                return 1;

            case R.id.row3:
                return 2;

            case R.id.row4:
                return 3;

            default:
                throw new IndexOutOfBoundsException("Could not find row index of the given view.");
        }
    }


    public void updateServerStatus( final ServerStatusListener.Status status ) {
        // Update server status
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (status) {
                    case CONNECTED:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_connected );
                        break;
                    case DISCONNECTED:
                    case DISABLED:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_disconnected );
                        break;
                    case READY:
                    case CONNECTING:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_searching );
                        break;
                    case UPLOADING:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_uploading );
                        break;
                    case UPLOADING_FAILED:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_uploading_failed );
                        break;
                    default:
                        mServerStatusIcon.setBackgroundResource( R.drawable.status_disconnected );
                }
            }
        });
    }

    public void updateServerRecordsSent(String keyNameTrigger, int numberOfRecordsTrigger)
    {
        // Condensing the message
        keyNameTrigger = keyNameTrigger.replaceFirst("_?android_?","");
        keyNameTrigger = keyNameTrigger.replaceFirst("_?empatica_?(e4)?","E4");

        String messageTimeStamp = timeFormat.format( System.currentTimeMillis() );
        String message;
        if ( numberOfRecordsTrigger < 0 ) {
            message = String.format(Locale.US, "%1$25s has FAILED uploading (%2$s)", keyNameTrigger, messageTimeStamp);
        } else {
            message = String.format(Locale.US, "%1$25s uploaded %2$4d records (%3$s)", keyNameTrigger, numberOfRecordsTrigger, messageTimeStamp);
        }

        mServerMessage.setText( message );
        logger.info(message);

        // TODO: more reliable way to get the row index. E.g. via the mConnections.
        int rowIndex;
        if (keyNameTrigger.contains("E4")) {
            rowIndex = 0;
        } else if (keyNameTrigger.contains("pebble")) {
            rowIndex = 2;
        } else if (keyNameTrigger.contains("phone")) {
            rowIndex = 3;
        } else {
            logger.info("Could not match the key name {} to a row in the ui", keyNameTrigger);
            return;
        }

        mTotalRecordsSent[rowIndex] += numberOfRecordsTrigger;
        mLastRecordsSentTimeMillis[rowIndex] = System.currentTimeMillis();
    }

    public void dialogInputDeviceName(final View v) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Device Serial Number:");

        // Set up the input
        final EditText input = new EditText(this);
        // Specify the type of input expected
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Setup the row
        final int row;
        try {
            row = getRowIndexFromView(v);
        } catch (IndexOutOfBoundsException iobe) {
            Boast.makeText(this, "Could not set this device key, there is no valid row index "
                    + "associated with this button.", Toast.LENGTH_LONG).show();
            logger.warn(iobe.getMessage());
            return;
        }

        // Set up the buttons
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String oldValue = mInputDeviceKeys[row];
                mInputDeviceKeys[row] = input.getText().toString();
                mDeviceInputButtons[row].setText( mInputDeviceKeys[row] );

                // Do NOT disconnect if input has not changed, is empty or equals the connected device.
                if (!mInputDeviceKeys[row].equals(oldValue) &&
                    !mInputDeviceKeys[row].isEmpty()        &&
                    !mConnections[row].isAllowedDevice( mInputDeviceKeys[row] ) )
                {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (mConnections[row].isRecording()) {
                                    mConnections[row].stopRecording();
                                    // will restart recording once the status is set to disconnected.
                                }
                            } catch (RemoteException e) {
                                logger.error("Cannot restart scanning");
                            }
                        }
                    });
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }
}