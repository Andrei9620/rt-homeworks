package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equalsIgnoreCase("exit")) {
                System.out.println("Oprire consola...");
                break;
            } else if (line.equalsIgnoreCase("help")) {
                printHelp();
            } else if (line.equalsIgnoreCase("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        // Separam la primul spatiu alb in maxim 2 bucati:
        // parts[0] -> deviceId ("device-1")
        // parts[1] -> comanda cu parametrii ("SET_INTERVAL 1000" sau "RESET")
        String[] parts = line.split("\\s+", 2);

        if (parts.length < 2) {
            System.err.println("[EROARE CONSOLA] Format comanda invalid!");
            System.err.println("Format asteptat: <deviceId> <COMANDA> [parametru]");
            System.err.println("Exemple: device-1 SET_INTERVAL 1000 | device-1 STATUS | device-1 RESET");
            return;
        }

        String deviceId = parts[0].trim();
        String commandPayload = parts[1].trim();

        try {
            String topic = Config.commandTopic(deviceId);
            mqtt.publish(topic, commandPayload);
            System.out.printf("[COMANDĂ TRIMISĂ] Către: %s | Pe topic: %s | Conținut: %s%n",
                    deviceId, topic, commandPayload);
        } catch (Exception e) {
            System.err.println("[EROARE MQTT] Nu s-a putut trimite comanda: " + e.getMessage());
        }
    }

    private void printHelp() {
        System.out.println("\nComenzi disponibile:");
        System.out.println("  show                              -> Afiseaza starea curenta a device-urilor");
        System.out.println("  <deviceId> SET_INTERVAL <ms>      -> Schimba perioada taskului periodic");
        System.out.println("  <deviceId> SET_THRESHOLD <val>    -> Schimba pragul de temperatura pentru alarma");
        System.out.println("  <deviceId> RESET                  -> Reseteaza senzorul la valoarea initiala");
        System.out.println("  <deviceId> STATUS                 -> Cere perioada si pragul curent");
        System.out.println("  help                              -> Afiseaza acest mesaj");
        System.out.println("  exit                              -> Opreste aplicatia\n");
    }
}