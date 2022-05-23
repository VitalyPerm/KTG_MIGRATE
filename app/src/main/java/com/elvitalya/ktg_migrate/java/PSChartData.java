package com.elvitalya.ktg_migrate.java;

import com.luckcome.lmtpdecorder.data.FhrData;

/**
 * Created by lavrik on 19.12.2017.
 */

public class PSChartData {

    //region Public fields
    public int fhr;
    public byte toco;
    public float time;
    public byte devicePower;
    public boolean hasToco;
    //endregion

    //region Public static methods
    public static PSChartData map(FhrData fhrData) {
        PSChartData data = new PSChartData();
        data.fhr = fhrData.fhr1;
        data.toco = fhrData.toco;
        data.devicePower = fhrData.devicePower;
        data.hasToco = fhrData.isHaveToco == 1;
        return data;
    }
    //endregion

    @Override
    public String toString() {
        return "FHR: " + fhr +
                " TOCO: " + toco +
                " TIME: " + time +
                " POWER: " + devicePower +
                " HAS TOCO: " + hasToco;
    }
}