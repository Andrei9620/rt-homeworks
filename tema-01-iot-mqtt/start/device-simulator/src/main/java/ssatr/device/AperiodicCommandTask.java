package ssatr.device;

import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluăm comanda din coadă (blochează firul până la sosirea unei comenzi)
                String rawCommand = commands.take();
                if (rawCommand == null || rawCommand.trim().isEmpty()) {
                    continue;
                }

                String trimmed = rawCommand.trim();
                String[] parts = trimmed.split("\\s+");
                String action = parts[0].toUpperCase();
                String statusResult;

                // 2. Parsăm și executăm comanda
                try {
                    switch (action) {
                        case "SET_INTERVAL":
                            if (parts.length < 2) {
                                throw new IllegalArgumentException("Lipseste parametrul <ms>");
                            }
                            long interval = Long.parseLong(parts[1]);
                            if (interval <= 0) {
                                throw new IllegalArgumentException("Intervalul trebuie sa fie strict pozitiv");
                            }
                            periodic.setPeriodMs(interval);
                            statusResult = "OK " + trimmed;
                            break;

                        case "SET_THRESHOLD":
                            if (parts.length < 2) {
                                throw new IllegalArgumentException("Lipseste parametrul <valoare>");
                            }
                            double threshold = Double.parseDouble(parts[1]);
                            periodic.setThreshold(threshold);
                            statusResult = "OK " + trimmed;
                            break;

                        case "RESET":
                            sensor.reset();
                            statusResult = "OK RESET";
                            break;

                        case "STATUS":
                            statusResult = String.format(java.util.Locale.US,
                                    "OK STATUS period=%d threshold=%.2f",
                                    periodic.getPeriodMs(), periodic.getThreshold());
                            break;

                        default:
                            throw new IllegalArgumentException("Comanda necunoscuta: " + parts[0]);
                    }
                } catch (NumberFormatException e) {
                    statusResult = "ERROR Parametru numeric invalid: " + e.getMessage();
                } catch (IllegalArgumentException e) {
                    statusResult = "ERROR " + e.getMessage();
                } catch (Exception e) {
                    statusResult = "ERROR Executie esuata: " + e.getMessage();
                }

                // 3. Format: deviceId;timestamp;<statusResult>
                long timestamp = System.currentTimeMillis();
                String payload = deviceId + ";" + timestamp + ";" + statusResult;

                // 4. Publicăm confirmarea pe topicul de status
                mqtt.publish(Config.statusTopic(deviceId), payload);

            } catch (InterruptedException e) {
                // Semnal de oprire a firului de execuție
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
