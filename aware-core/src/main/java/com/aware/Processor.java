
package com.aware;

import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.aware.providers.Processor_Provider;
import com.aware.providers.Processor_Provider.Processor_Data;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.Aware_Sensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * Service that logs CPU activity on the device
 *
 * @author df
 */
public class Processor extends Aware_Sensor {

    /**
     * Broadcasted event: when there is new processor usage information
     */
    public static final String ACTION_AWARE_PROCESSOR = "ACTION_AWARE_PROCESSOR";

    /**
     * Broadcasted event: fired when the processor idle is below 10%
     */
    public static final String ACTION_AWARE_PROCESSOR_STRESSED = "ACTION_AWARE_PROCESSOR_STRESSED";

    /**
     * Broadcasted event: fired when the processor idle is above 90%
     */
    public static final String ACTION_AWARE_PROCESSOR_RELAXED = "ACTION_AWARE_PROCESSOR_RELAXED";

    private static Handler mHandler = new Handler();
    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {

            HashMap<String, Integer> processorNow = getProcessorLoad();

            float user_percentage = 0, system_percentage = 0, idle_percentage = 0;

            Cursor lastProcessor = getContentResolver().query(Processor_Data.CONTENT_URI, null, null, null, Processor_Data.TIMESTAMP + " DESC LIMIT 1");
            if (lastProcessor != null && lastProcessor.moveToFirst()) {
                int oldUser = lastProcessor.getInt(lastProcessor.getColumnIndex(Processor_Data.LAST_USER));
                int oldSystem = lastProcessor.getInt(lastProcessor.getColumnIndex(Processor_Data.LAST_SYSTEM));
                int oldIdle = lastProcessor.getInt(lastProcessor.getColumnIndex(Processor_Data.LAST_IDLE));

                int delta_user = processorNow.get("user") - oldUser;
                int delta_system = processorNow.get("system") - oldSystem;
                int delta_idle = processorNow.get("idle") - oldIdle;

                try {
                    user_percentage = delta_user * 100.f / (delta_user + delta_system + delta_idle);
                    system_percentage = delta_system * 100.f / (delta_user + delta_system + delta_idle);
                    idle_percentage = delta_idle * 100.f / (delta_user + delta_system + delta_idle);
                } catch (ArithmeticException e) {
                }
            }
            if (lastProcessor != null && !lastProcessor.isClosed()) lastProcessor.close();

            if (Aware.DEBUG)
                Log.d(TAG, "USER: " + user_percentage + "% SYSTEM: " + system_percentage + "% IDLE: " + idle_percentage + "% Total: " + (user_percentage + system_percentage + idle_percentage));

            ContentValues rowData = new ContentValues();
            rowData.put(Processor_Data.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Processor_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Processor_Data.LAST_USER, processorNow.get("user"));
            rowData.put(Processor_Data.LAST_SYSTEM, processorNow.get("system"));
            rowData.put(Processor_Data.LAST_IDLE, processorNow.get("idle"));
            rowData.put(Processor_Data.USER_LOAD, user_percentage);
            rowData.put(Processor_Data.SYSTEM_LOAD, system_percentage);
            rowData.put(Processor_Data.IDLE_LOAD, idle_percentage);

            try {
                getContentResolver().insert(Processor_Data.CONTENT_URI, rowData);
            } catch (SQLiteException e) {
                if (Aware.DEBUG) Log.d(TAG, e.getMessage());
            } catch (SQLException e) {
                if (Aware.DEBUG) Log.d(TAG, e.getMessage());
            } catch (IllegalStateException e) {
                if (Aware.DEBUG) Log.d(TAG, e.getMessage());
            }

            Intent newProcessor = new Intent(ACTION_AWARE_PROCESSOR);
            sendBroadcast(newProcessor);

            if (idle_percentage <= 10) {
                Intent stressed = new Intent(ACTION_AWARE_PROCESSOR_STRESSED);
                sendBroadcast(stressed);
            }

            if (idle_percentage >= 90) {
                Intent relaxed = new Intent(ACTION_AWARE_PROCESSOR_RELAXED);
                sendBroadcast(relaxed);
            }

            mHandler.postDelayed(mRunnable, Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_PROCESSOR)) * 1000);
        }
    };

    private static int FREQUENCY = -1;

    private final IBinder serviceBinder = new ServiceBinder();

    /**
     * Activity-Service binder
     */
    public class ServiceBinder extends Binder {
        Processor getService() {
            return Processor.getService();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }

    private static Processor processorSrv = Processor.getService();

    /**
     * Singleton instance of this service
     *
     * @return {@link Processor} obj
     */
    public static Processor getService() {
        if (processorSrv == null) processorSrv = new Processor();
        return processorSrv;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        DATABASE_TABLES = Processor_Provider.DATABASE_TABLES;
        TABLES_FIELDS = Processor_Provider.TABLES_FIELDS;
        CONTEXT_URIS = new Uri[]{Processor_Data.CONTENT_URI};

        if (Aware.DEBUG) Log.d(TAG, "Processor service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_PROCESSOR).length() == 0) {
            Aware.setSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_PROCESSOR, 10);
        }

        Aware.setSetting(this, Aware_Preferences.STATUS_PROCESSOR, true);
        if (FREQUENCY != Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_PROCESSOR))) {
            mHandler.removeCallbacks(mRunnable);
            mHandler.post(mRunnable);
            FREQUENCY = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_PROCESSOR));
        }

        if (Aware.DEBUG) Log.d(TAG, "Processor service active: " + FREQUENCY + "s");

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mHandler.removeCallbacks(mRunnable);

        if (Aware.DEBUG) Log.d(TAG, "Processor service terminated...");
    }

    /**
     * Get processor load from /proc/stat and returns an hashmap with the values:
     * [user]
     * [system]
     * [idle]
     *
     * @return {@link HashMap} with user, system and idle keys and values
     */
    public static HashMap<String, Integer> getProcessorLoad() {
        HashMap<String, Integer> processor = new HashMap<String, Integer>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")), 5000);
            String line = "";
            //NOTE: CPU  USER NICE SYSTEM IDLE - there are two spaces after CPU
            if ((line = reader.readLine()) != null) {
                String[] items = line.split(" ");
                processor.put("user", Integer.parseInt(items[2]) + Integer.parseInt(items[3]));
                processor.put("system", Integer.parseInt(items[4]));
                processor.put("idle", Integer.parseInt(items[5]));
            }
            if (reader != null) reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return processor;
    }
}
