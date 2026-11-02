// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// ===================== Definiții globale =====================

// Prioritățile trebuie să fie între 2 și 19.
#define PRIO_PERIODIC   8
#define PRIO_SPORADIC   6
#define PRIO_APERIODIC  4
#define TASK_STACK      4096   // octeți

// Dacă nu e definit deja în config.h

// Eveniment de alarmă (pus în coadă prin valoare)
struct AlarmEvent {
    float temperature;
    float threshold;
};

// Comandă (textul însuși, nu pointer)
struct Command {
    char text[COMMAND_MAX_LEN];
};

static QueueHandle_t alarm_queue   = NULL;
static QueueHandle_t command_queue = NULL;

// Citite și modificate din taskuri diferite
static volatile uint32_t period_ms = PERIOD_MS_INITIAL;
static volatile float    threshold = THRESHOLD_INITIAL;

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
float read_temperature(void) {
    return temperatureRead();
}

// ===================== TODO 1: TASK PERIODIC =====================

static void task_periodic(void *arg) {
    TickType_t last = xTaskGetTickCount();   // r_0

    for (;;) {
        // Așteptare ABSOLUTĂ: până la last + T, apoi last se actualizează singur.
        TickType_t ticks = pdMS_TO_TICKS(period_ms);
        if (ticks < 1) ticks = 1;
        xTaskDelayUntil(&last, ticks);

        // --- jobul ---
        float t   = read_temperature();
        float thr = threshold;

        char msg[96];
        snprintf(msg, sizeof(msg), "%s;%lld;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), t);
        mqtt_publish("telemetry", msg);
        Serial.printf("[periodic] telemetrie: %s\n", msg);

        if (t > thr) {
            AlarmEvent ev = { t, thr };
            // timeout 0: taskul periodic nu se blochează niciodată
            if (xQueueSend(alarm_queue, &ev, 0) != pdTRUE) {
                Serial.println("[periodic] coada de alarme plina, eveniment pierdut");
            }
        }
    }
}

// ===================== TODO 2: TASK SPORADIC =====================

static void task_sporadic(void *arg) {
    uint32_t last_published_ms = 0;
    bool have_last = false;

    for (;;) {
        AlarmEvent ev;
        // Blochează până sosește un eveniment
        xQueueReceive(alarm_queue, &ev, portMAX_DELAY);

        uint32_t now = millis();   // timp monoton
        if (have_last && (now - last_published_ms) < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] alarma RESPINSA (%lu ms < %lu ms)\n",
                          (unsigned long)(now - last_published_ms),
                          (unsigned long)MIN_INTERARRIVAL_MS);
            continue;
        }

        last_published_ms = now;
        have_last = true;

        char msg[128];
        snprintf(msg, sizeof(msg), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), ev.temperature, ev.threshold);
        mqtt_publish("alarm", msg);
        Serial.printf("[sporadic] alarma publicata: %s\n", msg);
    }
}

// ===================== TODO 3: TASK APERIODIC =====================

static void publish_status(const char *text) {
    char msg[200];
    snprintf(msg, sizeof(msg), "%s;%lld;%s",
             DEVICE_ID, (long long)epoch_ms(), text);
    mqtt_publish("status", msg);
    Serial.printf("[aperiodic] status: %s\n", msg);
}

static void execute_command(char *line) {
    // Elimină spațiile de la capete
    while (*line == ' ' || *line == '\t') line++;
    size_t n = strlen(line);
    while (n > 0 && (line[n - 1] == ' ' || line[n - 1] == '\t' ||
                     line[n - 1] == '\r' || line[n - 1] == '\n')) {
        line[--n] = '\0';
    }

    char *saveptr = NULL;
    char *cmd = strtok_r(line, " \t", &saveptr);
    char *arg = strtok_r(NULL, " \t", &saveptr);
    char out[160];

    if (cmd == NULL) {
        publish_status("ERROR comanda goala");
        return;
    }

    if (strcmp(cmd, "SET_INTERVAL") == 0) {
        char *end = NULL;
        long v = arg ? strtol(arg, &end, 10) : 0;
        if (arg == NULL || *end != '\0' || v <= 0) {
            snprintf(out, sizeof(out), "ERROR SET_INTERVAL parametru invalid: %s",
                     arg ? arg : "(lipsa)");
            publish_status(out);
            return;
        }
        period_ms = (uint32_t)v;
        snprintf(out, sizeof(out), "OK SET_INTERVAL %ld", v);
        publish_status(out);

    } else if (strcmp(cmd, "SET_THRESHOLD") == 0) {
        char *end = NULL;
        float v = arg ? strtof(arg, &end) : 0.0f;
        if (arg == NULL || *end != '\0') {
            snprintf(out, sizeof(out), "ERROR SET_THRESHOLD parametru invalid: %s",
                     arg ? arg : "(lipsa)");
            publish_status(out);
            return;
        }
        threshold = v;
        snprintf(out, sizeof(out), "OK SET_THRESHOLD %.2f", v);
        publish_status(out);

    } else if (strcmp(cmd, "RESET") == 0) {
        period_ms = PERIOD_MS_INITIAL;
        threshold = THRESHOLD_INITIAL;
        publish_status("OK RESET");

    } else if (strcmp(cmd, "STATUS") == 0) {
        snprintf(out, sizeof(out), "OK STATUS interval=%lu threshold=%.2f",
                 (unsigned long)period_ms, (float)threshold);
        publish_status(out);

    } else {
        snprintf(out, sizeof(out), "ERROR comanda necunoscuta: %s", cmd);
        publish_status(out);
    }
}

static void task_aperiodic(void *arg) {
    for (;;) {
        Command c;
        xQueueReceive(command_queue, &c, portMAX_DELAY);
        Serial.printf("[aperiodic] execut: %s\n", c.text);
        execute_command(c.text);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
// Rulează pe taskul clientului MQTT: doar pune în coadă, fără blocare.
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    Command c;
    strlcpy(c.text, text, sizeof(c.text));   // copiem textul, nu pointerul
    if (xQueueSend(command_queue, &c, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi plina, comanda ignorata");
    }
}

// ===================== setup() / loop() =====================

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere (vezi explicația din schelet)
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): cozile, ÎNAINTE de mqtt_start()
    alarm_queue   = xQueueCreate(8, sizeof(AlarmEvent));
    command_queue = xQueueCreate(8, sizeof(Command));

    mqtt_start();

    // TODO 0 (b): taskurile, DUPĂ mqtt_start()
    xTaskCreate(task_periodic,  "periodic",  TASK_STACK, NULL, PRIO_PERIODIC,  NULL);
    xTaskCreate(task_sporadic,  "sporadic",  TASK_STACK, NULL, PRIO_SPORADIC,  NULL);
    xTaskCreate(task_aperiodic, "aperiodic", TASK_STACK, NULL, PRIO_APERIODIC, NULL);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}