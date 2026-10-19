package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // Pragul implicit raportat în mesajul de alarmă
    private double threshold = 28.0;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long lastReleaseTime = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluăm valoarea temperaturii din coadă (așteaptă blocant până când apare o depășire)
                Double temp = alarmEvents.take();
                long now = System.currentTimeMillis();

                // 2. Impunem intervalul minim între sosiri (MIN_INTERARRIVAL_MS)
                if (now - lastReleaseTime >= MIN_INTERARRIVAL_MS) {
                    lastReleaseTime = now;

                    // Format cerut: deviceId;timestamp;temperatura;prag
                    String payload = String.format(Locale.US, "%s;%d;%.2f;%.2f",
                            deviceId, now, temp, threshold);

                    mqtt.publish(Config.alarmTopic(deviceId), payload);
                    System.out.println("[ALARM SENT] " + payload);
                } else {
                    // Evenimentul a sosit prea repede -> respins conform modelului sporadic
                    long diff = now - lastReleaseTime;
                    System.out.println("[SPORADIC REJECTED] Alarma ignorata pentru " + deviceId
                            + " (au trecut doar " + diff + " ms din minimul de " + MIN_INTERARRIVAL_MS + " ms)");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[SporadicAlarmTask] Eroare: " + e.getMessage());
            }
        }
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }
}