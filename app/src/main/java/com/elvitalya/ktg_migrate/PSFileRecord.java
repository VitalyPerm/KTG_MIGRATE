package com.elvitalya.ktg_migrate;

import com.luckcome.lmtpdecorder.record.WaveFileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PSFileRecord {

    //region Private fields
    private WaveFileHeader mWaveFileHeader;
    private File mWaveFile;
    private FileOutputStream mWaveFileOutputStream;
    private int dataLength;
    //endregion

    //region Private constants
    private static final String RECORDS_SUBDIR = "records";
    //endregion

    //region Public constructors
    public PSFileRecord() {
    }
    //endregion

    //region Public methods
    public File prepareWaveFile(File fileDir, String fileName) {
        File root = new File(fileDir, RECORDS_SUBDIR);
        root.mkdir();
        clearRoot(root);
        this.dataLength = 0;

        try {
            this.mWaveFile = new File(root, fileName);
            this.mWaveFile.createNewFile();
            this.mWaveFileOutputStream = new FileOutputStream(this.mWaveFile, true);
            this.mWaveFileHeader = new WaveFileHeader();
            this.mWaveFileHeader.writeFileHeader(this.mWaveFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mWaveFile;

    }

    public void continueWaveFile() {
        try {
            this.mWaveFileOutputStream = new FileOutputStream(this.mWaveFile, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void writeWaveData(byte[] wave) {
        if (this.mWaveFileOutputStream != null) {
            try {
                this.mWaveFileOutputStream.write(wave);
                this.mWaveFileOutputStream.flush();
                this.dataLength += wave.length;
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public void finish() {
        try {
            this.mWaveFileOutputStream.close();
            this.mWaveFileOutputStream = null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.mWaveFileHeader.setDataSize(this.dataLength);
        this.mWaveFileHeader.writeFileHeader(this.mWaveFile);
    }
    //endregion

    //region Private methods
    private File clearRoot(File root) {
        File[] files = root.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                file.delete();
            }
        }
        return root;
    }
    //endregion
}
