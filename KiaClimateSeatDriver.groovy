/**
 * Kia Climate Seat Control Driver
 * 
 * Child device driver for controlling heated/cooled seats via Kia UVO Connect
 * Supports ThermostatMode (heat/cool/off) and FanControl (intensity levels)
 * 
 * Author: Jeremy Akers
 * Version: 1.0.0
 */

metadata {
    definition(
        name: "Kia Climate Seat Control",
        namespace: "kia-uvo",
        author: "Jeremy Akers",
        importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/KiaClimateSeatDriver.groovy"
    ) {
        capability "ThermostatMode"
        capability "FanControl"
        
        attribute "thermostatMode", "enum", ["off", "heat", "cool"]
        attribute "speed", "enum", ["off", "low", "medium", "high"]
        attribute "supportedThermostatModes", "JSON_OBJECT"
        attribute "supportedFanSpeeds", "JSON_OBJECT"
        
        command "off"
        command "heat"
        command "cool"
        command "setThermostatMode", [[name: "mode", type: "ENUM"]]
        command "setSpeed", [[name: "speed", type: "ENUM"]]
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    // Set default values
    sendEvent(name: "thermostatMode", value: "off")
    sendEvent(name: "speed", value: "off")
}

// ThermostatMode commands
def off() {
    sendEvent(name: "thermostatMode", value: "off")
    sendEvent(name: "speed", value: "off")
}

def heat() {
    sendEvent(name: "thermostatMode", value: "heat")
    if (device.currentValue("speed") == "off") {
        sendEvent(name: "speed", value: "low")
    }
}

def cool() {
    sendEvent(name: "thermostatMode", value: "cool")
    if (device.currentValue("speed") == "off") {
        sendEvent(name: "speed", value: "low")
    }
}

def setThermostatMode(mode) {
    sendEvent(name: "thermostatMode", value: mode)
    if (mode == "off") {
        sendEvent(name: "speed", value: "off")
    }
}

// FanControl commands
def setSpeed(speed) {
    sendEvent(name: "speed", value: speed)
    if (speed != "off" && device.currentValue("thermostatMode") == "off") {
        sendEvent(name: "thermostatMode", value: "heat")
    }
}

