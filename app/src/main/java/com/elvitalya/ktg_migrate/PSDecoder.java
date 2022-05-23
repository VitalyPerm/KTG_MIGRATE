package com.elvitalya.ktg_migrate;


import com.luckcome.lmtpdecorder.LMTPDecoderListener;
import com.luckcome.lmtpdecorder.audio.MyAudioTrack8Bit;
import com.luckcome.lmtpdecorder.data.BluetoothData;
import com.luckcome.lmtpdecorder.data.FhrByteDataBuffer;
import com.luckcome.lmtpdecorder.data.FhrCommandMaker;
import com.luckcome.lmtpdecorder.data.FhrData;
import com.luckcome.lmtpdecorder.help.ADPCM;
import com.luckcome.lmtpdecorder.help.CycleThread;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class PSDecoder {

    //region Private fields
    private PipedInputStream mPipedInputStream;
    private PipedOutputStream mPipedOutputStream;
    private InputThread mInputThread;
    private FhrByteDataBuffer mByteDataBuffer;
    private DecodeThread mDecodeThread;
    private MyAudioTrack8Bit mMyAudioTrack8Bit;
    private LMTPDecoderListener mLMTPDecoderListener;
    private boolean isWorking;
    private boolean isRecording;
    private int fmCounter;
    private int tocoCounter;
    private PSFileRecord mFileRecord;
    //endregion

    //region Public constructors
    public PSDecoder() {
    }
    //endregion

    //region Public methods
    public boolean prepare() {
        this.mPipedInputStream = new PipedInputStream();
        this.mPipedOutputStream = new PipedOutputStream();

        try {
            this.mPipedOutputStream.connect(this.mPipedInputStream);
        } catch (IOException var4) {
            var4.printStackTrace();

            try {
                this.mPipedInputStream.close();
                this.mPipedOutputStream.close();
            } catch (IOException var3) {
                var3.printStackTrace();
            }

            this.mPipedInputStream = null;
            this.mPipedOutputStream = null;
            this.isWorking = false;
            return false;
        }

        this.mByteDataBuffer = new FhrByteDataBuffer();
        this.mMyAudioTrack8Bit = new MyAudioTrack8Bit();
        this.mMyAudioTrack8Bit.prepareAudioTrack();
        this.isWorking = false;
        return true;
    }

    public void startWork() {
        this.mInputThread = new InputThread();
        this.mDecodeThread = new DecodeThread();
        this.mDecodeThread.start();

        if (this.mInputThread != null) {
            this.mInputThread.start();
        }

        this.isWorking = true;
    }

    public void stopWork() {
        if (this.isRecording) {
            this.finishRecordWave();
        }

        this.isWorking = false;
        if (this.mInputThread != null) {
            this.mInputThread.cancel();
        }

        this.mInputThread = null;
        if (this.mDecodeThread != null) {
            this.mDecodeThread.cancel();
        }

        this.mDecodeThread = null;
    }

    public void release() {
        if (this.isWorking) {
            this.stopWork();
        }

        try {
            if (this.mPipedInputStream != null) {
                this.mPipedInputStream.close();
            }

            if (this.mPipedOutputStream != null) {
                this.mPipedOutputStream.close();
            }
        } catch (IOException var2) {
            var2.printStackTrace();
        }

        this.mPipedInputStream = null;
        this.mPipedOutputStream = null;
        this.mByteDataBuffer = null;
        this.mLMTPDecoderListener = null;
        this.mMyAudioTrack8Bit.releaseAudioTrack();
    }

    public boolean isWorking() {
        return this.isWorking;
    }

    public void putData(byte data) {
        if (this.isWorking) {
            try {
                this.mPipedOutputStream.write(data);
            } catch (IOException var3) {
                var3.printStackTrace();
            }
        }

    }

    public void putData(byte[] data) {
        if (this.isWorking) {
            try {
                this.mPipedOutputStream.write(data);
            } catch (IOException var3) {
                var3.printStackTrace();
            }
        }

    }

    public void putData(byte[] data, int offset, int length) {
        if (this.isWorking) {
            try {
                this.mPipedOutputStream.write(data, offset, length);
            } catch (IOException var5) {
                var5.printStackTrace();
            }
        }

    }

    private void dataAnalyze(BluetoothData data) {
        if (data.dataType == 2) {
            FhrData fhr = new FhrData();
            fhr.fhr1 = data.mValue[3] & 255;
            fhr.fhr2 = data.mValue[4] & 255;
            fhr.toco = data.mValue[5];
            fhr.afm = data.mValue[6];
            fhr.fhrSignal = (byte) (data.mValue[7] & 3);
            fhr.afmFlag = (byte) ((data.mValue[7] & 64) != 0 ? 1 : 0);
            fhr.devicePower = (byte) (data.mValue[8] & 7);
            fhr.isHaveFhr1 = (byte) ((data.mValue[8] & 16) != 0 ? 1 : 0);
            fhr.isHaveFhr2 = (byte) ((data.mValue[8] & 32) != 0 ? 1 : 0);
            fhr.isHaveToco = (byte) ((data.mValue[8] & 64) != 0 ? 1 : 0);
            fhr.isHaveAfm = (byte) ((data.mValue[8] & 128) != 0 ? 1 : 0);
            if (this.getFM()) {
                fhr.fmFlag = 1;
            }

            if (this.getToco()) {
                fhr.tocoFlag = 1;
            }

            if (this.mLMTPDecoderListener != null) {
                this.mLMTPDecoderListener.fhrDataChanged(fhr);
            }
        } else if (data.dataType == 1) {
            byte[] value = new byte[200];
            ADPCM.decodeAdpcm(value, 0, data.mValue, 3, 100, data.mValue[104], data.mValue[105], data.mValue[106]);
            if (this.mMyAudioTrack8Bit != null) {
                this.mMyAudioTrack8Bit.writeAudioTrack(value, 0, 200);
            }

            if (this.isRecording && this.mFileRecord != null) {
                this.mFileRecord.writeWaveData(value);
            }
        }

    }

    public boolean continueRecordWave() {
        if (!this.isWorking) {
            this.isRecording = false;
        } else {
            if (mFileRecord != null) {
                this.mFileRecord.continueWaveFile();
                this.isRecording = true;
            }
        }

        return this.isRecording;
    }

    public File beginRecordWave(File directory, String file) {
        if (!this.isWorking) {
            this.isRecording = false;
        } else {
            this.mFileRecord = new PSFileRecord();
            this.isRecording = true;
            return this.mFileRecord.prepareWaveFile(directory, file);
        }

        return null;
    }

    public void finishRecordWave() {
        if (this.isWorking) {
            if (this.isRecording && this.mFileRecord != null) {
                this.isRecording = false;
                this.mFileRecord.finish();
            }

        }
    }

    public boolean isRecording() {
        return this.isRecording;
    }

    private synchronized boolean getFM() {
        boolean flag = false;
        if (this.fmCounter > 0) {
            --this.fmCounter;
            flag = true;
        }

        return flag;
    }

    public synchronized void setFM() {
        ++this.fmCounter;
    }

    private synchronized boolean getToco() {
        boolean flag = false;
        if (this.tocoCounter > 0) {
            --this.tocoCounter;
            flag = true;
        }

        return flag;
    }

    private synchronized void setToco() {
        ++this.tocoCounter;
    }

    public void setLMTPDecoderListener(LMTPDecoderListener listener) {
        this.mLMTPDecoderListener = listener;
    }

    public void sendTocoReset(int value) {
        if (this.mLMTPDecoderListener != null) {
            this.mLMTPDecoderListener.sendCommand(FhrCommandMaker.tocoReset(value));
        }

        this.setToco();
    }

    public void sendTocoResetNoCount(int value) {
        if (this.mLMTPDecoderListener != null) {
            this.mLMTPDecoderListener.sendCommand(FhrCommandMaker.tocoReset(value));
        }

    }

    public void sendFhrVolue(int value) {
        if (this.mLMTPDecoderListener != null) {
            this.mLMTPDecoderListener.sendCommand(FhrCommandMaker.fhrVolume(value));
        }

    }

    public void sendFhrAlarmVolue(int value) {
        if (this.mLMTPDecoderListener != null) {
            this.mLMTPDecoderListener.sendCommand(FhrCommandMaker.alarmVolume(value));
        }

    }

    public void sendFhrAlarmLevel(int level) {
        if (this.mLMTPDecoderListener != null) {
            this.mLMTPDecoderListener.sendCommand(FhrCommandMaker.alarmLevel(level));
        }

    }

    public String getVersion() {
        return "Luckcome mobile terminal protocol version \"2.3-20160329\"";
    }
    //endregion

    //region classes
    class DecodeThread extends CycleThread {
        DecodeThread() {
        }

        public void cycle() {
            if (PSDecoder.this.mByteDataBuffer.canRead()) {
                BluetoothData btd = PSDecoder.this.mByteDataBuffer.getBag();
                if (btd != null) {
                    PSDecoder.this.dataAnalyze(btd);
                }
            } else {
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException var2) {
                    var2.printStackTrace();
                }
            }

        }
    }

    class InputThread extends CycleThread {
        byte[] buffer = new byte[1024];
        int len = 0;

        InputThread() {
        }

        public void cycle() {
            try {
                this.len = PSDecoder.this.mPipedInputStream.read(this.buffer);
                PSDecoder.this.mByteDataBuffer.addDatas(this.buffer, 0, this.len);
            } catch (IOException var2) {
                var2.printStackTrace();
            }

        }
    }
    //endregion
}