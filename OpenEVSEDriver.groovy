/**
 * OpenEVSE WiFi Driver for Hubitat
 * 
 * Monitors OpenEVSE charging station status and provides real-time updates
 * for vehicle plug/unplug events, charging state, energy usage, and more.
 * 
 * Features:
 * - REST API polling for status updates
 * - MQTT support for real-time event notifications
 * - Automatic vehicle status refresh on plug/unplug events
 * - Energy monitoring (kWh, power, current, voltage)
 * - Standard Hubitat capabilities (Switch, EnergyMeter, PowerMeter, VoltageMeasurement, CurrentMeasurement)
 * 
 * Author: Jeremy Akers
 * Date: 2025-12-02
 * License: MIT
 * 
 * API Documentation: https://openevse.stoplight.io/docs/openevse-wifi-v4/
 */

metadata {
    definition (
        name: "OpenEVSE WiFi", 
        namespace: "jeremyakers", 
        author: "Jeremy Akers",
        importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/OpenEVSEDriver.groovy"
    ) {
        capability "Switch"
        capability "EnergyMeter"
        capability "PowerMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "Refresh"
        capability "Initialize"
        
        // OpenEVSE State attributes
        attribute "state", "enum", ["unknown", "not connected", "connected", "charging", "vent required", "diode check failed", "gfci fault", "no ground", "stuck relay", "gfci self-test failed", "over temperature", "over current", "sleeping", "disabled"]
        attribute "vehicleConnected", "enum", ["true", "false"]
        attribute "stateCode", "number"
        
        // Charging attributes
        attribute "chargingCurrent", "number"  // Amps
        attribute "maxCurrent", "number"  // Max available amps
        attribute "sessionEnergy", "number"  // kWh for current session
        attribute "totalEnergy", "number"  // Total lifetime kWh
        attribute "sessionTime", "number"  // Minutes for current session
        attribute "temperature", "number"  // °C
        
        // WiFi/System attributes
        attribute "ipAddress", "string"
        attribute "ssid", "string"
        attribute "rssi", "number"
        attribute "firmware", "string"
        attribute "hostname", "string"
        
        // Status display
        attribute "statusHtml", "string"
        
        // Commands
        command "enableCharging"
        command "disableCharging"
        command "setChargingCurrent", [[name: "current", type: "NUMBER", description: "Current in amps (6-48)"]]
    }
}

preferences {
    section("OpenEVSE Connection") {
        input "ipAddress", "text", title: "OpenEVSE IP Address", required: true
        input "port", "number", title: "Port", defaultValue: 80, required: true
        input "refreshInterval", "number", title: "Status Refresh Interval (seconds)", defaultValue: 30, range: "10..300", required: true
    }
    
    section("MQTT Settings (Optional - for real-time updates)") {
        input "enableMqtt", "bool", title: "Enable MQTT", defaultValue: false
        input "mqttBroker", "text", title: "MQTT Broker IP:Port", description: "e.g., 192.168.1.100:1883"
        input "mqttUsername", "text", title: "MQTT Username (optional)"
        input "mqttPassword", "password", title: "MQTT Password (optional)"
        input "mqttBaseTopic", "text", title: "MQTT Base Topic", defaultValue: "openevse", description: "Base topic for OpenEVSE (default: openevse)"
    }
    
    section("Logging") {
        input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
    }
}

def installed() {
    log.info "OpenEVSE driver installed"
    initialize()
}

def updated() {
    log.info "OpenEVSE driver updated"
    unschedule()
    
    // Disconnect MQTT if settings changed
    if (interfaces.mqtt.isConnected()) {
        interfaces.mqtt.disconnect()
    }
    
    initialize()
}

def initialize() {
    log.info "Initializing OpenEVSE driver..."
    
    // Clear previous state
    state.clear()
    state.lastVehicleConnected = null
    state.lastStateCode = null
    
    // Initial refresh
    runIn(2, refresh)
    
    // Schedule periodic refresh
    if (refreshInterval && refreshInterval > 0) {
        schedule("0/${refreshInterval} * * * * ?", refresh)
        log.info "Scheduled status refresh every ${refreshInterval} seconds"
    }
    
    // Connect to MQTT if enabled
    if (enableMqtt && mqttBroker) {
        runIn(5, connectMqtt)
    }
}

def refresh() {
    if (debugLogging) log.debug "Refreshing OpenEVSE status..."
    getStatus()
    getConfig()
}

// ============================================================================
// REST API Methods
// ============================================================================

def getStatus() {
    def uri = "http://${ipAddress}:${port}/status"
    
    try {
        httpGet(uri) { response ->
            if (response.status == 200) {
                parseStatusResponse(response.data)
            } else {
                log.error "HTTP ${response.status} from ${uri}"
            }
        }
    } catch (Exception e) {
        log.error "Failed to get status from OpenEVSE: ${e.message}"
    }
}

def getConfig() {
    def uri = "http://${ipAddress}:${port}/config"
    
    try {
        httpGet(uri) { response ->
            if (response.status == 200) {
                parseConfigResponse(response.data)
            } else {
                if (debugLogging) log.debug "HTTP ${response.status} from ${uri}"
            }
        }
    } catch (Exception e) {
        if (debugLogging) log.debug "Failed to get config from OpenEVSE: ${e.message}"
    }
}

def parseStatusResponse(data) {
    if (debugLogging) log.debug "Parsing status response: ${data}"
    
    // State code and text
    def stateCode = data.state ?: 0
    def stateText = getStateText(stateCode)
    def previousStateCode = state.lastStateCode
    
    sendEvent(name: "stateCode", value: stateCode)
    sendEvent(name: "state", value: stateText)
    
    // Vehicle connection status
    def vehicleConnected = (stateCode >= 2)  // State 2+ means vehicle is connected
    def previousVehicleConnected = state.lastVehicleConnected
    
    sendEvent(name: "vehicleConnected", value: vehicleConnected.toString())
    
    // Switch capability (on = charging enabled/active)
    def isCharging = (stateCode == 3)  // State 3 = charging
    sendEvent(name: "switch", value: isCharging ? "on" : "off")
    
    // Charging current and voltage (amp is in mA, voltage and pilot are already in V and A)
    if (data.amp != null) {
        def current = (data.amp / 1000).round(2)  // Convert mA to A and round to 2 decimal places
        sendEvent(name: "chargingCurrent", value: current, unit: "A")
        sendEvent(name: "amperage", value: current)  // CurrentMeasurement capability
    }
    
    if (data.pilot != null) {
        def maxCurrent = data.pilot.round(2)  // Already in A, just round to 2 decimal places
        sendEvent(name: "maxCurrent", value: maxCurrent, unit: "A")
    }
    
    if (data.voltage != null) {
        def voltage = data.voltage.round(2)  // Already in V, just round to 2 decimal places
        sendEvent(name: "voltage", value: voltage, unit: "V")
    }
    
    // Power calculation (amp is in mA, voltage is in V)
    if (data.amp != null && data.voltage != null) {
        def power = ((data.amp / 1000) * data.voltage / 1000).round(2)  // Convert mA to A, multiply by V, then to kW and round to 2 decimal places
        sendEvent(name: "power", value: power, unit: "kW")
    }
    
    // Energy
    if (data.session_wh != null) {
        def sessionEnergy = (data.session_wh / 1000).round(2)  // Convert Wh to kWh and round to 2 decimal places
        sendEvent(name: "sessionEnergy", value: sessionEnergy, unit: "kWh")
    }
    
    if (data.watthour != null) {
        def totalEnergy = (data.watthour / 1000).round(2)  // Convert Wh to kWh and round to 2 decimal places
        sendEvent(name: "totalEnergy", value: totalEnergy, unit: "kWh")
        sendEvent(name: "energy", value: totalEnergy)  // EnergyMeter capability
    }
    
    // Session time
    if (data.elapsed != null) {
        def sessionMinutes = (data.elapsed / 60).round(2)  // Convert seconds to minutes and round to 2 decimal places
        sendEvent(name: "sessionTime", value: sessionMinutes, unit: "min")
    }
    
    // Temperature
    if (data.temp != null) {
        def tempCelsius = (data.temp / 10).round(2)  // Convert deci-degrees to Celsius and round to 2 decimal places
        sendEvent(name: "temperature", value: tempCelsius, unit: "°C")
    }
    
    // Update HTML status display
    updateStatusHtml()
    
    // Log plug/unplug events for Rule Machine triggers
    if (previousVehicleConnected != null && previousVehicleConnected != vehicleConnected) {
        if (vehicleConnected) {
            log.info "🔌 Vehicle PLUGGED IN detected"
        } else {
            log.info "🔌 Vehicle UNPLUGGED detected"
        }
    }
    
    // Store current state for next comparison
    state.lastVehicleConnected = vehicleConnected
    state.lastStateCode = stateCode
}

def parseConfigResponse(data) {
    if (debugLogging) log.debug "Parsing config response: ${data}"
    
    // WiFi info
    if (data.ssid) {
        sendEvent(name: "ssid", value: data.ssid)
    }
    
    if (data.rssi != null) {
        sendEvent(name: "rssi", value: data.rssi, unit: "dBm")
    }
    
    // System info
    if (data.firmware) {
        sendEvent(name: "firmware", value: data.firmware)
    }
    
    if (data.hostname) {
        sendEvent(name: "hostname", value: data.hostname)
    }
    
    if (data.ipaddress) {
        sendEvent(name: "ipAddress", value: data.ipaddress)
    }
}

def getStateText(stateCode) {
    switch (stateCode) {
        case 0: return "unknown"
        case 1: return "not connected"
        case 2: return "connected"
        case 3: return "charging"
        case 4: return "vent required"
        case 5: return "diode check failed"
        case 6: return "gfci fault"
        case 7: return "no ground"
        case 8: return "stuck relay"
        case 9: return "gfci self-test failed"
        case 10: return "over temperature"
        case 254: return "sleeping"
        case 255: return "disabled"
        default: return "unknown (${stateCode})"
    }
}

// ============================================================================
// MQTT Methods
// ============================================================================

def connectMqtt() {
    if (!enableMqtt || !mqttBroker) {
        if (debugLogging) log.debug "MQTT not enabled or broker not configured"
        return
    }
    
    try {
        def brokerParts = mqttBroker.split(":")
        def broker = brokerParts[0]
        def mqttPort = brokerParts.size() > 1 ? brokerParts[1].toInteger() : 1883
        
        def clientId = "hubitat-openevse-${device.id}"
        
        if (mqttUsername && mqttPassword) {
            interfaces.mqtt.connect(
                "tcp://${broker}:${mqttPort}",
                clientId,
                mqttUsername,
                mqttPassword
            )
        } else {
            interfaces.mqtt.connect(
                "tcp://${broker}:${mqttPort}",
                clientId,
                null,
                null
            )
        }
        
        log.info "🔌 Connecting to MQTT broker: ${broker}:${mqttPort}"
    } catch (Exception e) {
        log.error "Failed to connect to MQTT broker: ${e.message}"
    }
}

def mqttClientStatus(String status) {
    log.info "MQTT Status: ${status}"
    
    if (status.startsWith("Connection succeeded")) {
        def baseTopic = mqttBaseTopic ?: "openevse"
        
        // Subscribe to all relevant topics
        interfaces.mqtt.subscribe("${baseTopic}/state")
        interfaces.mqtt.subscribe("${baseTopic}/amp")
        interfaces.mqtt.subscribe("${baseTopic}/voltage")
        interfaces.mqtt.subscribe("${baseTopic}/pilot")
        interfaces.mqtt.subscribe("${baseTopic}/temp")
        interfaces.mqtt.subscribe("${baseTopic}/session_wh")
        interfaces.mqtt.subscribe("${baseTopic}/watthour")
        interfaces.mqtt.subscribe("${baseTopic}/elapsed")
        
        log.info "✅ MQTT connected and subscribed to ${baseTopic}/# topics"
    } else if (status.startsWith("Connection lost")) {
        log.warn "MQTT connection lost, will retry..."
        runIn(30, connectMqtt)
    }
}

def parse(String description) {
    // Handle MQTT messages
    def message = interfaces.mqtt.parseMessage(description)
    if (debugLogging) log.debug "MQTT message received: ${message.topic} = ${message.payload}"
    
    def baseTopic = mqttBaseTopic ?: "openevse"
    def topic = message.topic.replace("${baseTopic}/", "")
    def value = message.payload
    
    switch (topic) {
        case "state":
            def stateCode = value.toInteger()
            def stateText = getStateText(stateCode)
            def previousStateCode = state.lastStateCode
            
            sendEvent(name: "stateCode", value: stateCode)
            sendEvent(name: "state", value: stateText)
            
            // Vehicle connection detection
            def vehicleConnected = (stateCode >= 2)
            def previousVehicleConnected = state.lastVehicleConnected
            
            sendEvent(name: "vehicleConnected", value: vehicleConnected.toString())
            
            // Log plug/unplug events for Rule Machine triggers
            if (previousVehicleConnected != null && previousVehicleConnected != vehicleConnected) {
                if (vehicleConnected) {
                    log.info "🔌 [MQTT] Vehicle PLUGGED IN detected"
                } else {
                    log.info "🔌 [MQTT] Vehicle UNPLUGGED detected"
                }
            }
            
            state.lastVehicleConnected = vehicleConnected
            state.lastStateCode = stateCode
            
            // Update switch status
            def isCharging = (stateCode == 3)
            sendEvent(name: "switch", value: isCharging ? "on" : "off")
            break
            
        case "amp":
            def current = (value.toFloat() / 1000).round(2)  // Convert mA to A and round to 2 decimal places
            sendEvent(name: "chargingCurrent", value: current, unit: "A")
            sendEvent(name: "amperage", value: current)
            break
            
        case "voltage":
            def voltage = value.toFloat().round(2)  // Already in V, just round to 2 decimal places
            sendEvent(name: "voltage", value: voltage, unit: "V")
            break
            
        case "pilot":
            def maxCurrent = value.toFloat().round(2)  // Already in A, just round to 2 decimal places
            sendEvent(name: "maxCurrent", value: maxCurrent, unit: "A")
            break
            
        case "temp":
            def tempCelsius = (value.toFloat() / 10).round(2)  // Convert deci-degrees to Celsius and round to 2 decimal places
            sendEvent(name: "temperature", value: tempCelsius, unit: "°C")
            break
            
        case "session_wh":
            def sessionEnergy = (value.toFloat() / 1000).round(2)  // Convert Wh to kWh and round to 2 decimal places
            sendEvent(name: "sessionEnergy", value: sessionEnergy, unit: "kWh")
            break
            
        case "watthour":
            def totalEnergy = (value.toFloat() / 1000).round(2)  // Convert Wh to kWh and round to 2 decimal places
            sendEvent(name: "totalEnergy", value: totalEnergy, unit: "kWh")
            sendEvent(name: "energy", value: totalEnergy)
            break
            
        case "elapsed":
            def sessionMinutes = (value.toInteger() / 60).round(2)  // Convert seconds to minutes and round to 2 decimal places
            sendEvent(name: "sessionTime", value: sessionMinutes, unit: "min")
            break
    }
    
    updateStatusHtml()
}

// ============================================================================
// Command Methods
// ============================================================================

def on() {
    enableCharging()
}

def off() {
    disableCharging()
}

def enableCharging() {
    log.info "Enabling charging..."
    sendCommand("enable")
}

def disableCharging() {
    log.info "Disabling charging..."
    sendCommand("disable")
}

def setChargingCurrent(current) {
    if (current < 6 || current > 48) {
        log.error "Current must be between 6 and 48 amps"
        return
    }
    
    log.info "Setting charging current to ${current}A..."
    sendCommand("current/${current}")
}

def sendCommand(String command) {
    def uri = "http://${ipAddress}:${port}/r?rapi=\$${command}"
    
    try {
        httpGet(uri) { response ->
            if (response.status == 200) {
                if (debugLogging) log.debug "Command successful: ${command}"
                runIn(2, refresh)  // Refresh after command
            } else {
                log.error "Command failed: HTTP ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Failed to send command: ${e.message}"
    }
}

// ============================================================================
// HTML Status Display
// ============================================================================

def updateStatusHtml() {
    def state = device.currentValue("state") ?: "Unknown"
    def vehicleConnected = device.currentValue("vehicleConnected") == "true"
    def current = device.currentValue("chargingCurrent") ?: 0
    def voltage = device.currentValue("voltage") ?: 0
    def power = device.currentValue("power") ?: 0
    def sessionEnergy = device.currentValue("sessionEnergy") ?: 0
    def sessionTime = device.currentValue("sessionTime") ?: 0
    def temperature = device.currentValue("temperature") ?: "N/A"
    def maxCurrent = device.currentValue("maxCurrent") ?: 0
    
    // Format numeric values to 2 decimal places
    def powerStr = power instanceof Number ? sprintf("%.2f", power) : power
    def sessionEnergyStr = sessionEnergy instanceof Number ? sprintf("%.2f", sessionEnergy) : sessionEnergy
    def sessionTimeStr = sessionTime instanceof Number ? sprintf("%.1f", sessionTime) : sessionTime
    def voltageStr = voltage instanceof Number ? sprintf("%.1f", voltage) : voltage
    def currentStr = current instanceof Number ? sprintf("%.1f", current) : current
    def maxCurrentStr = maxCurrent instanceof Number ? sprintf("%.1f", maxCurrent) : maxCurrent
    def temperatureStr = temperature instanceof Number ? sprintf("%.1f", temperature) : temperature
    
    // Status color
    def stateColor = "#6c757d"  // gray default
    switch (state) {
        case "charging":
            stateColor = "#28a745"  // green
            break
        case "connected":
            stateColor = "#17a2b8"  // blue
            break
        case ~/.*fault.*/:
        case ~/.*failed.*/:
            stateColor = "#dc3545"  // red
            break
    }
    
    def html = """
        <div style="font-family: Arial, sans-serif; font-size: 14px;">
            <h3 style="color: #1f77b4; margin-bottom: 10px;">OpenEVSE</h3>
            <table style="width: 100%; border-collapse: collapse;">
                <tr><td style="padding: 2px; font-weight: bold;">Status:</td><td style="padding: 2px; color: ${stateColor};">${state}</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Vehicle:</td><td style="padding: 2px;">${vehicleConnected ? "🔌 Connected" : "❌ Not Connected"}</td></tr>
    """
    
    if (vehicleConnected) {
        html += """
                <tr><td style="padding: 2px; font-weight: bold;">Current:</td><td style="padding: 2px;">${currentStr} A</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Voltage:</td><td style="padding: 2px;">${voltageStr} V</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Power:</td><td style="padding: 2px;">${powerStr} kW</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Session Energy:</td><td style="padding: 2px;">${sessionEnergyStr} kWh</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Session Time:</td><td style="padding: 2px;">${sessionTimeStr} min</td></tr>
        """
    }
    
    html += """
                <tr><td style="padding: 2px; font-weight: bold;">Max Current:</td><td style="padding: 2px;">${maxCurrentStr} A</td></tr>
                <tr><td style="padding: 2px; font-weight: bold;">Temperature:</td><td style="padding: 2px;">${temperatureStr}°C</td></tr>
            </table>
        </div>
    """
    
    sendEvent(name: "statusHtml", value: html)
}

