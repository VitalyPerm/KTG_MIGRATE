package com.elvitalya.ktg_migrate

import com.elvitalya.ktg_migrate.java.PSChartData
import java.io.File

interface ExaminationData {

    class Monitor(
        val data: MutableList<PSChartData>,
        val movePoints: ArrayList<Float>,
        val reconnectPoints: ArrayList<Float>
    ) {
        fun clear() {
            data.clear()
            movePoints.clear()
            reconnectPoints.clear()
        }

        val datasetDuration: Float
            get() = if (data.isNotEmpty()) {
                data[data.size - 1].time
            } else 0f


        fun addReconnectPoint(time: Float) {
            if (!reconnectPoints.contains(time)) {
                reconnectPoints.add(time)
            }
        }
    }

    class Record(val duration: Int, val record: File)
}