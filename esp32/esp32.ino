#include <Arduino.h>
#include <SD.h>
#include <cstring>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

void logExecution(const String& message);

#include "ble.h"
#include "config.h"
#include "keyval.h"
#include "preferences.h"
#include "scheduler.h"
#include "theme.h"
#include "ui.h"

#include <queue>

std::queue<String> navigationQueue{};
bool connectionChanged = true;
bool oldIsOverspeed    = false;

namespace {
	const char* EXECUTION_LOG_PATH = "/catdrive.log";
	const char* EXECUTION_LOG_ROTATED_1 = "/catdrive.log.1";
	const char* EXECUTION_LOG_ROTATED_2 = "/catdrive.log.2";
	const size_t EXECUTION_LOG_MAX_SIZE = 1024 * 1024;
	const size_t EXECUTION_LOG_ENTRY_SIZE = 512;

	struct ExecutionLogEntry {
		char text[EXECUTION_LOG_ENTRY_SIZE];
	};

	QueueHandle_t executionLogQueue = nullptr;
	File executionLogFile;
	bool executionLogInitAttempted = false;
	bool executionLogAvailable = false;
}

void logExecution(const String& message) {
	const String line = String("[") + millis() + " ms] " + message;
	Serial.println(line);

	if (executionLogQueue == nullptr ||
	    (executionLogInitAttempted && !executionLogAvailable))
		return;

	ExecutionLogEntry entry{};
	line.toCharArray(entry.text, sizeof(entry.text));
	if (xQueueSend(executionLogQueue, &entry, 0) != pdTRUE) {
		Serial.println("Execution log queue full; this event was not saved to SD");
	}
}

void initializeExecutionLog() {
	if (!SD.begin(PIN_SD_CS, SPI, 20000000)) {
		executionLogInitAttempted = true;
		if (executionLogQueue != nullptr)
			xQueueReset(executionLogQueue);
		logExecution("SD initialization failed; execution logging is Serial-only");
		return;
	}

	executionLogFile = SD.open(EXECUTION_LOG_PATH, FILE_APPEND);
	if (!executionLogFile) {
		executionLogInitAttempted = true;
		if (executionLogQueue != nullptr)
			xQueueReset(executionLogQueue);
		logExecution("Could not open /catdrive.log; execution logging is Serial-only");
		return;
	}

	executionLogInitAttempted = true;
	executionLogAvailable = true;
	executionLogFile.println();
	executionLogFile.println("=== CatDrive ESP32 execution log ===");
	executionLogFile.flush();
	logExecution("SD execution logging started: /catdrive.log");
}

void rotateExecutionLogIfNeeded(size_t incomingBytes) {
	if (!executionLogFile || executionLogFile.size() + incomingBytes <= EXECUTION_LOG_MAX_SIZE)
		return;

	executionLogFile.flush();
	executionLogFile.close();

	if (SD.exists(EXECUTION_LOG_ROTATED_2) && !SD.remove(EXECUTION_LOG_ROTATED_2)) {
		logExecution("Could not remove oldest SD execution log");
		return;
	}
	if (SD.exists(EXECUTION_LOG_ROTATED_1) &&
	    !SD.rename(EXECUTION_LOG_ROTATED_1, EXECUTION_LOG_ROTATED_2)) {
		logExecution("Could not rotate SD execution log .1");
		return;
	}
	if (SD.exists(EXECUTION_LOG_PATH) &&
	    !SD.rename(EXECUTION_LOG_PATH, EXECUTION_LOG_ROTATED_1)) {
		logExecution("Could not rotate current SD execution log");
		return;
	}

	executionLogFile = SD.open(EXECUTION_LOG_PATH, FILE_APPEND);
	if (!executionLogFile)
		logExecution("Could not reopen SD execution log after rotation");
}

void processExecutionLogQueue() {
	if (executionLogQueue == nullptr || !executionLogFile)
		return;

	ExecutionLogEntry entry{};
	bool wroteEntries = false;
	while (xQueueReceive(executionLogQueue, &entry, 0) == pdTRUE) {
		const size_t lineSize = strlen(entry.text) + 2;
		rotateExecutionLogIfNeeded(lineSize);
		if (!executionLogFile)
			return;

		if (executionLogFile.println(entry.text) == 0) {
			Serial.println("Could not write to SD execution log");
			executionLogFile.close();
			return;
		}
		wroteEntries = true;
	}

	if (wroteEntries)
		executionLogFile.flush();
}

void onCharacteristicWrite(const String& uuid, uint8_t* data, size_t length) {
	String value = (uuid != CHA_NAV_TBT_ICON) ? String((char*)(data)) : String();

	if (uuid == CHA_SETTINGS) {
		const auto kv = kvParseMultiline(value);

		Pref::lightTheme = kv.getOrDefault("lightTheme", "false") == "true";
		Pref::brightness = kv.getOrDefault("brightness", "100").toInt();
		Pref::speedLimit = kv.getOrDefault("speedLimit", "60").toInt();

		lcd.setBrightness(Pref::brightness);
		Pref::lightTheme ? ThemeControl::light() : ThemeControl::dark();

		if (kv.contains("removeAllFiles")) {
			Data::removeAllFiles();
		}
	}

	if (uuid == CHA_NAV) {
		navigationQueue.push(value);
		pongNavigation();
	}

	if (uuid == CHA_NAV_TBT_ICON) {
		int semicolonIndex = -1;
		for (uint8_t i = 0; i < 16; i++) {
			if (data[i] == ';') {
				semicolonIndex = i;
				break;
			}
		}

		if (semicolonIndex <= 0) {
			logExecution("Rejected navigation icon: no hash found");
			return;
		}

		String iconHash = String((char*)data, semicolonIndex);
		auto iconSize   = length - semicolonIndex - 1;
		logExecution(String("Navigation icon received: hash=") + iconHash + ", bytes=" + iconSize);

		if (iconSize != ICON_BITMAP_BUFFER_SIZE) {
			logExecution("Rejected navigation icon: invalid bitmap size");
			return;
		}

		Data::receiveNewIcon(iconHash, data + semicolonIndex + 1);

		pongNavigation();
	}

	if (uuid == CHA_GPS_SPEED) {
		navigationQueue.push(String("speed=") + value);

		pongSpeed();
	}
}

void onConnectionChange(bool connected) {
	connectionChanged = true;
}

uint32_t gLastNavigationDataReceived_ms = 0;
uint32_t gLastSpeedDataReceived_ms      = 0;

void pongNavigation() {
	gLastNavigationDataReceived_ms = millis();
}

void pongSpeed() {
	gLastSpeedDataReceived_ms = millis();
}

void processQueue() {
	if (navigationQueue.empty())
		return;

	const auto& data = navigationQueue.front();
	const auto kv    = kvParseMultiline(data);
	logExecution(String("Applying received data: ") + data);

	if (kv.contains("nextRd")) {
		Data::setNextRoad(kv.getOrDefault("nextRd"));
	}
	if (kv.contains("nextRdDesc")) {
		Data::setNextRoadDesc(kv.getOrDefault("nextRdDesc"));
	}
	if (kv.contains("distToNext")) {
		Data::setDistanceToNextTurn(kv.getOrDefault("distToNext"));
	}
	if (kv.contains("totalDist")) {
		Data::setTotalDistance(kv.getOrDefault("totalDist"));
	}
	if (kv.contains("eta")) {
		Data::setEta(kv.getOrDefault("eta"));
	}
	if (kv.contains("ete")) {
		Data::setEte(kv.getOrDefault("ete"));
	}
	if (kv.contains("iconHash")) {
		Data::setIconHash(kv.getOrDefault("iconHash"));
	}
	if (kv.contains("speed")) {
		Data::setSpeed(kv.getOrDefault("speed").toInt());
	}

	navigationQueue.pop();
}

void setup() {
	delay(2000);

	Serial.begin(115200);
	executionLogQueue = xQueueCreate(32, sizeof(ExecutionLogEntry));
	logExecution("ESP32 boot");
	Serial.println("Initializing BLE...");
	initBle();

	Serial.println("Initializing UI...");
	UI::init();
	initializeExecutionLog();

	Data::init();

	lcd.setBrightness(Pref::brightness);
	ThemeControl::dark();

	logExecution("ESP32 initialization complete");
}

bool isOverspeed(int speed) {
	return speed >= Pref::speedLimit;
}

void loop() {
	processExecutionLogQueue();
	UI::update();
	ThemeControl::update();
	Data::update();

	processQueue();
	processExecutionLogQueue();

	// Overspeed check
	const auto newIsOverspeed = isOverspeed(Data::speed());
	if (newIsOverspeed != oldIsOverspeed) {
		oldIsOverspeed = newIsOverspeed;

		if (newIsOverspeed)
			ThemeControl::flashScreen();
	}

	DO_EVERY(10000) {
		if (isOverspeed(Data::speed())) {
			ThemeControl::flashScreen();
		}
	}

	// Connection status
	if (connectionChanged) {
		connectionChanged = false;

		if (!deviceConnected) {
			navigationQueue = std::queue<String>();
			Data::clearNavigationData();
			Data::clearSpeedData();
			Data::setNextRoadDesc("Disconnected!");
		}
	}
}
