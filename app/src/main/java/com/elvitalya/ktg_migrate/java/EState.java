package com.elvitalya.ktg_migrate.java;

import androidx.annotation.IntDef;
import android.util.Log;

import com.elvitalya.ktg_migrate.ExaminationData;
import com.elvitalya.ktg_migrate.ExaminationMetaInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class EState {

    public final long timestamp;
    protected final ExaminationData.Monitor mMonitorData;
    protected int mConnectionStatus = STATUS_DISCONNECTED;

    protected static boolean isStatesLogEnabled;

    protected static final List<EState> sStatesLog = new ArrayList<>();
    protected static final String TAG = EState.class.getSimpleName();

    private float totalRecordDuration;

    public static final int STATUS_CONNECTED = 11;
    public static final int STATUS_CONNECTING = 22;
    public static final int STATUS_DISCONNECTED = 33;
    /**
     * некоторые смартфорны имеют трудности с подключением. Подключение к bluetooth устройству
     * происходит успешно, осуществляется подписка на характеристику с данными монитора, начинают приходить
     * данные, но через ~1 секунду происходит отключение. В таком случае осуществляем повторную попытку
     * поключиться, но через сокет
     */
    public static final int STATUS_UNSTABLE = 44;

    @IntDef({STATUS_CONNECTED, STATUS_CONNECTING, STATUS_DISCONNECTED, STATUS_UNSTABLE})
    @interface ConnectionStatus {
    }

    public void connected() {
        mConnectionStatus = STATUS_CONNECTED;
    }

    public void connectionStarted() {
        mConnectionStatus = STATUS_CONNECTING;
    }

    public void disconnected() {
        mConnectionStatus = STATUS_DISCONNECTED;
    }

    public void connectionUnstable() {
        mConnectionStatus = STATUS_UNSTABLE;
    }

    public int getConnectionStatus() {
        return mConnectionStatus;
    }

    public void setConnectionStatus(@ConnectionStatus int connectionStatus) {
        mConnectionStatus = connectionStatus;
    }

    public void setTotalRecordDuration(float total) {
        this.totalRecordDuration = total;
    }

    public float getTotalRecordDuration() {
        return totalRecordDuration;
    }

    public boolean isConnectingOrConnected() {
        return mConnectionStatus == STATUS_CONNECTING || mConnectionStatus == STATUS_CONNECTED;
    }

    protected EState(ExaminationData.Monitor monitorData) {
        if (isStatesLogEnabled) {
            sStatesLog.add(this);
            Log.e(TAG, "New state: " + this.toString());
        }
        timestamp = System.currentTimeMillis();
        mMonitorData = monitorData;
    }

    public ExaminationData.Monitor getMonitorData() {
        logUnsupportedOperation();
        return null;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " Status: " + mConnectionStatus;
    }

    public static void enableLog() {
        clearLog();
        isStatesLogEnabled = true;
    }

    public static void clearLog() {
        sStatesLog.clear();
    }

    public static void disableLog() {
        clearLog();
        isStatesLogEnabled = false;
    }

    public static List<EState> getStatesLog() {
        return sStatesLog;
    }

    public static void printLogs() {
        for (EState state : sStatesLog) {
            Log.e(TAG + " state:", state.toString());
        }
    }

    public static class Empty extends EState {

        public Empty() {
            super(new ExaminationData.Monitor(Collections.synchronizedList(new ArrayList<>()),
                    new ArrayList<>(), new ArrayList<>()));
        }

        @Override
        public ExaminationData.Monitor getMonitorData() {
            return mMonitorData;
        }
    }

    public static class Recording extends EState {

        public Recording(ExaminationData.Monitor monitorData) {
            super(monitorData);
        }

        @Override
        public ExaminationData.Monitor getMonitorData() {
            return mMonitorData;
        }
    }

    public static class Pause extends EState {

        public Pause(ExaminationData.Monitor monitorData) {
            super(monitorData);
        }

        @Override
        public ExaminationData.Monitor getMonitorData() {
            return mMonitorData;
        }
    }

    public static abstract class Complete extends EState {

        private final ExaminationData.Record mRecordData;

        public Complete(ExaminationData.Monitor monitorData, ExaminationData.Record record) {
            super(monitorData);
            mRecordData = record;
        }

        protected ExaminationMetaInfo getExaminationModel() {
            logUnsupportedOperation();
            return null;
        }

        public ExaminationData.Record getRecordData() {
            return mRecordData;
        }

        @Override
        public ExaminationData.Monitor getMonitorData() {
            return mMonitorData;
        }

        public static class Success extends Complete {

            private final ExaminationMetaInfo mExaminationModel;

            public Success(ExaminationData.Monitor monitorData, ExaminationData.Record mRecordData, ExaminationMetaInfo metaInfo) {
                super(monitorData, mRecordData);
                mExaminationModel = metaInfo;
            }

            @Override
            public ExaminationMetaInfo getExaminationModel() {
                return mExaminationModel;
            }

        }

        public static class Saving extends Complete {
            public Saving(ExaminationData.Monitor monitorData, ExaminationData.Record recordData) {
                super(monitorData, recordData);
            }
        }
    }

    protected void logUnsupportedOperation() {
        Log.e(TAG, "State " + this.getClass().getSimpleName() + " has not such data!");
    }

}
