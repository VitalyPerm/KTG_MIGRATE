package com.elvitalya.ktg_migrate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public interface ExaminationData {

    class Monitor {
        public final List<PSChartData> data;
        public final ArrayList<Float> movePoints;
        public final ArrayList<Float> reconnectPoints;

        public Monitor(List<PSChartData> data, ArrayList<Float> movePoints, ArrayList<Float> reconnectPoints) {
            this.data = data;
            this.movePoints = movePoints;
            this.reconnectPoints = reconnectPoints;
        }

        public void clear() {
            data.clear();
            movePoints.clear();
            reconnectPoints.clear();
        }

        public float getDatasetDuration() {
            if (!data.isEmpty()) {
                return data.get(data.size() - 1).time;
            } else {
                return 0;
            }
        }

        public void addReconnectPoint(float time) {
            if (!reconnectPoints.contains(time)) {
                reconnectPoints.add(time);
            }
        }
    }

    class Record {
        public final int duration;
        public final File record;

        public Record(int duration, File record) {
            this.duration = duration;
            this.record = record;
        }
    }
}
