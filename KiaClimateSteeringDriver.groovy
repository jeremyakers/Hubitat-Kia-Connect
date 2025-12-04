/**
 * Kia Climate Steering Wheel Control Driver
 * 
 * Child device driver for controlling heated steering wheel via Kia UVO Connect
 * Supports FanControl capability with customizable speed levels
 * 
 * Author: Jeremy Akers
 * Version: 1.0.0
 */

metadata {
    definition(
        name: "Kia Climate Steering Wheel Control",
        namespace: "kia-uvo",
        author: "Jeremy Akers",
        importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/KiaClimateSteeringDriver.groovy"
    ) {
        capability "FanControl"
        
        attribute "speed", "enum", ["off", "on", "low", "high"]
        attribute "supportedFanSpeeds", "JSON_OBJECT"
        
        command "off"
        command "on"
        command "low"
        command "high"
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
    // Set default value
    sendEvent(name: "speed", value: "off")
}

// FanControl commands
def off() {
    sendEvent(name: "speed", value: "off")
}

def on() {
    sendEvent(name: "speed", value: "on")
}

def low() {
    sendEvent(name: "speed", value: "low")
}

def high() {
    sendEvent(name: "speed", value: "high")
}

def setSpeed(speed) {
    sendEvent(name: "speed", value: speed)
}

