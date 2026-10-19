package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long nextRelease = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Așteptare până la următorul moment din grilă (fără derivă temporală)
                long now = System.currentTimeMillis();
                long sleepTime = nextRelease - now;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }

                // 2. Execuție job (C): citire senzor și generare timestamp
                double temp = sensor.read();
                long timestamp = System.currentTimeMillis();

                // Format cerut: deviceId;timestamp;temperatura (cu punct zecimal)
                String payload = String.format(Locale.US, "%s;%d;%.2f", deviceId, timestamp, temp);
                mqtt.publish(Config.telemetryTopic(deviceId), payload);

                // 3. Verificare condiție de alarmă
                if (temp > threshold) {
                    // Punem valoarea temperaturii în coada taskului sporadic
                    alarmEvents.offer(temp);
                }

                // 4. Avansare grilă fixă pentru următorul job
                nextRelease += periodMs;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[PeriodicTelemetryTask] Eroare: " + e.getMessage());
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}