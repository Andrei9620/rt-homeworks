package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    /**
     * Reține starea internă a unui singur dispozitiv.
     */
    public static class DeviceState {
        private final String deviceId;
        private double lastTemperature;
        private long lastTimestamp;
        private int alarmCount;
        private String lastStatus = "-";

        public DeviceState(String deviceId) {
            this.deviceId = deviceId;
        }

        public synchronized void updateTelemetry(long timestamp, double temperature) {
            this.lastTimestamp = timestamp;
            this.lastTemperature = temperature;
        }

        public synchronized void updateAlarm(long timestamp, double temperature, double threshold) {
            this.lastTimestamp = timestamp;
            this.lastTemperature = temperature;
            this.alarmCount++;
        }

        public synchronized void updateStatus(long timestamp, String status) {
            this.lastTimestamp = timestamp;
            this.lastStatus = status;
        }

        public synchronized double getLastTemperature() {
            return lastTemperature;
        }

        public synchronized long getLastTimestamp() {
            return lastTimestamp;
        }

        public synchronized int getAlarmCount() {
            return alarmCount;
        }

        public synchronized String getLastStatus() {
            return lastStatus;
        }

        public String getDeviceId() {
            return deviceId;
        }
    }

    // Colectie concurenta indexata dupa deviceId
    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();

    /**
     * Actualizeaza telemetria primita de la un device.
     */
    public void recordTelemetry(String deviceId, long timestamp, double temp) {
        devices.computeIfAbsent(deviceId, DeviceState::new).updateTelemetry(timestamp, temp);
    }

    /**
     * Inregistreaza declansarea unei alarme si actualizeaza contorul.
     */
    public void recordAlarm(String deviceId, long timestamp, double temp, double threshold) {
        devices.computeIfAbsent(deviceId, DeviceState::new).updateAlarm(timestamp, temp, threshold);
    }

    /**
     * Inregistreaza confirmarea unei comenzi sau textul de status.
     */
    public void recordStatus(String deviceId, long timestamp, String statusText) {
        devices.computeIfAbsent(deviceId, DeviceState::new).updateStatus(timestamp, statusText);
    }

    /**
     * Afișează un tabel formatat cu starea tuturor device-urilor înregistrate.
     */
    public void print() {
        if (devices.isEmpty()) {
            System.out.println("\n[DASHBOARD] Niciun dispozitiv conectat pana acum.\n");
            return;
        }

        long now = System.currentTimeMillis();
        System.out.println("\n================================= DASHBOARD =================================");
        System.out.printf(Locale.US, "%-12s | %-12s | %-16s | %-8s | %-20s%n",
                "DEVICE", "TEMP (°C)", "ULTIMUL MESAJ", "ALARME", "ULTIMUL STATUS");
        System.out.println("-------------+--------------+------------------+----------+---------------------");

        for (DeviceState state : devices.values()) {
            double temp;
            long lastTime;
            int alarms;
            String status;

            synchronized (state) {
                temp = state.getLastTemperature();
                lastTime = state.getLastTimestamp();
                alarms = state.getAlarmCount();
                status = state.getLastStatus();
            }

            long ageMs = Math.max(0, now - lastTime);
            String ageStr;
            if (ageMs < 1000) {
                ageStr = "acum " + ageMs + " ms";
            } else {
                ageStr = "acum " + (ageMs / 1000) + " s";
            }

            System.out.printf(Locale.US, "%-12s | %10.2f   | %-16s | %8d | %-20s%n",
                    state.getDeviceId(), temp, ageStr, alarms, status);
        }
        System.out.println("=============================================================================\n");
    }
}