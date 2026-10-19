package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluăm mesajul din coadă (blocant)
                String rawMessage = inbox.take();
                if (rawMessage == null || rawMessage.trim().isEmpty()) {
                    continue;
                }

                // 2. Salvăm mesajul brut în CSV (data/telemetry.csv, data/alarm.csv etc.)
                storage.append(kind, rawMessage);

                // 3. Parsăm mesajul protejat împotriva formatelor invalide
                try {
                    String[] parts = rawMessage.split(";");
                    if (parts.length < 3) {
                        System.err.println("[MessageProcessor-" + kind + "] Mesaj ignorat (campuri insuficiente): " + rawMessage);
                        continue;
                    }

                    String deviceId = parts[0].trim();
                    long timestamp = Long.parseLong(parts[1].trim());

                    switch (kind) {
                        case "telemetry":
                            double temp = Double.parseDouble(parts[2].trim());
                            dashboard.recordTelemetry(deviceId, timestamp, temp);
                            break;

                        case "alarm":
                            double alarmTemp = Double.parseDouble(parts[2].trim());
                            double threshold = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 0.0;
                            dashboard.recordAlarm(deviceId, timestamp, alarmTemp, threshold);

                            // Alarmele se afișează imediat în consolă
                            System.out.printf(java.util.Locale.US,
                                    "%n>>> [ALARMA RECEPTIONATA] Dispozitiv: %s | Temp: %.2f °C | Prag: %.2f °C <<<%n%n",
                                    deviceId, alarmTemp, threshold);
                            break;

                        case "status":
                            String statusText = parts[2].trim();
                            dashboard.recordStatus(deviceId, timestamp, statusText);

                            // Confirmările/statusurile se afișează imediat în consolă
                            System.out.printf(java.util.Locale.US,
                                    "%n>>> [STATUS/CONFIRMARE] Dispozitiv: %s -> %s <<<%n%n",
                                    deviceId, statusText);
                            break;

                        default:
                            System.err.println("[MessageProcessor] Tip necunoscut de mesaj: " + kind);
                            break;
                    }

                } catch (NumberFormatException e) {
                    System.err.println("[MessageProcessor-" + kind + "] Eroare parsare numar in mesajul '" + rawMessage + "': " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("[MessageProcessor-" + kind + "] Eroare procesare continut mesaj: " + e.getMessage());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MessageProcessor-" + kind + "] Eroare neasteptata: " + e.getMessage());
            }
        }
    }
}