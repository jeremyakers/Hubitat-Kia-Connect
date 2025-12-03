/**
 * Kia UVO Vehicle Driver
 * 
 * Handles individual Kia vehicle monitoring and control
 * Uses the Kia UVO Connect API endpoints
 * 
 * Author: Jeremy Akers
 * Version: 1.0.0
 */

metadata {
    definition(
        name: "Kia UVO Vehicle Driver",
        namespace: "kia-uvo",
        author: "Jeremy Akers",
        description: "Driver for individual Kia vehicles connected through UVO Connect services",
        importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/KiaUVODriver.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"
        capability "Battery"
        capability "Switch"
        capability "Lock"
        capability "PresenceSensor"
        capability "ContactSensor"
        capability "ThermostatSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatHeatingSetpoint"

        // Vehicle Information
        attribute "NickName", "string"
        attribute "VIN", "string"
        attribute "Model", "string"
        attribute "ModelYear", "string"
        attribute "Trim", "string"
        attribute "Color", "string"
        attribute "Odometer", "string"
        attribute "LastRefreshTime", "string"

        // Vehicle Status
        attribute "DoorLocks", "string"
        attribute "Engine", "string"
        attribute "Hood", "string"
        attribute "Trunk", "string"
        attribute "Windows", "string"

        // Location
        attribute "Location", "string"
        attribute "Latitude", "string"
        attribute "Longitude", "string"
        attribute "GoogleMapsURL", "string"
        attribute "Speed", "number"
        attribute "Heading", "string"
        attribute "Altitude", "number"
        attribute "isHome", "string"

        // EV Specific (if applicable)
        attribute "isEV", "string"
        attribute "BatterySoC", "string"
        attribute "EVRange", "string"
        attribute "ChargingStatus", "string"
        attribute "PlugStatus", "string"
        attribute "ChargingPower", "number"
        attribute "ChargeTimeRemaining", "string"
        attribute "ChargeTimeRemainingMinutes", "string"
        attribute "ChargeTimeRemainingHours", "string"
        attribute "EstimatedChargeCompletionTime", "string"

        // Fuel (if applicable)
        attribute "FuelLevel", "string"
        attribute "FuelRange", "string"
        
        // 12V Auxiliary Battery
        attribute "AuxBattery", "number"

        // Environmental
        attribute "AirTemp", "number"
        attribute "OutsideTemp", "string"
        attribute "AirControl", "string"

        // Individual Door Status
        attribute "FrontLeftDoor", "string"
        attribute "FrontRightDoor", "string"
        attribute "BackLeftDoor", "string"
        attribute "BackRightDoor", "string"

        // Additional Status
        attribute "TotalRange", "string"
        attribute "Defrost", "string"

        // HTML Status Display (split into sections for dashboard tile 1024 char limit)
        attribute "vehicleInfoHtml", "string"
        attribute "batteryHtml", "string"
        attribute "chargingHtml", "string"
        attribute "doorsSecurityHtml", "string"
        attribute "locationHtml", "string"

        // MQTT
        attribute "mqttBatteryPublish", "string"

        // Vehicle Commands
        command "Lock"
        command "Unlock"
        command "StartClimate"
        command "StopClimate"
        command "HornAndLights"
        command "GetLocation"
        command "pollVehicle"  // Force a fresh vehicle status poll (may take 10-30 seconds)
    }
}

preferences {
    section("Vehicle Settings") {
        input "refreshInterval", "number", title: "Auto-refresh interval (minutes, 0 to disable)", defaultValue: 0, required: true
        input "climateTemp", "number", title: "Default Climate Temperature (°F)", defaultValue: 72, range: "60..85", required: true
        input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
    }
    
    section("Home Location") {
        input "homeLatitude", "decimal", title: "Home Latitude", required: false
        input "homeLongitude", "decimal", title: "Home Longitude", required: false
        input "homeRadius", "decimal", title: "Home Radius (miles)", defaultValue: 0.1, required: false
    }
    
    section("MQTT Settings (Optional)") {
        input "enableMqtt", "bool", title: "Enable MQTT Battery Publishing", defaultValue: false
        input "mqttBroker", "text", title: "MQTT Broker (IP:Port or http://webhook)", required: false
        input "mqttUsername", "text", title: "MQTT Username (optional)", required: false
        input "mqttPassword", "password", title: "MQTT Password (optional)", required: false
        input "mqttTopic", "text", title: "MQTT Topic", defaultValue: "hubitat/ev_battery_soc", required: false
    }
    
    section("Smart Polling Schedule") {
        input "longTermPollingHours", "number", title: "Long-term poll interval (hours, 0 to disable)", defaultValue: 6, range: "0..24", required: true, description: "Runs 24/7 to check vehicle status periodically"
        input "chargingPollingMinutes", "number", title: "Charging poll interval (minutes, 0 to disable)", defaultValue: 15, range: "0..60", required: true, description: "Only polls when vehicle is actively charging"
    }
}

def logDebug(msg) {
    if (debugLogging) {
        log.debug msg
    }
}

def installed() {
    log.info "Kia UVO Vehicle Driver installed for ${device.label}"
    initialize()
}

def updated() {
    log.info "Kia UVO Vehicle Driver updated for ${device.label}"
    unschedule()
    
    // Disconnect MQTT if settings changed
    if (interfaces.mqtt.isConnected()) {
        interfaces.mqtt.disconnect()
    }
    
    initialize()
}

def initialize() {
    logDebug "Initializing Kia UVO Vehicle Driver for ${device.label}"
    
    // Clear any existing schedules
    unschedule()
    
    // Set up long-term polling if enabled
    if (longTermPollingHours && longTermPollingHours > 0) {
        runIn(longTermPollingHours * 3600, longTermPoll)
        log.info "🕒 Long-term polling scheduled every ${longTermPollingHours} hours"
    }
    
    // Note: Charging polls are triggered by handleSmartPolling() when charging is detected
}

def refresh() {
    logDebug "Calling parent app to get cached vehicle status..."
    // Request cached status from parent app (LastRefreshTime will be updated only on successful API response)
    try {
        parent.getVehicleStatus(device)
        logDebug "Cached status request completed"
    } catch (Exception e) {
        log.error "Failed to call parent.getVehicleStatus: ${e.message}"
        // Set a failure indicator instead of success time
        sendEvent(name: "LastRefreshTime", value: "Failed: " + new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
    
    // Note: Smart polling is now handled by updateVehicleStatus() based on charging state
}

def longTermPoll() {
    log.info "🕒 Long-term polling for ${device.label} (preserves 12V battery)"
    
    try {
        // Poll the vehicle directly for fresh data (same as pollVehicle button)
        parent.refreshVehicleStatus(device, true)
        
        // Schedule next long-term poll if enabled
        if (longTermPollingHours && longTermPollingHours > 0) {
            runIn(longTermPollingHours * 3600, longTermPoll)
            if (debugLogging) log.debug "Next long-term poll scheduled in ${longTermPollingHours} hours"
        }
    } catch (Exception e) {
        log.error "Failed to perform long-term poll: ${e.message}"
        // Still schedule next poll to maintain schedule
        if (longTermPollingHours && longTermPollingHours > 0) {
            runIn(longTermPollingHours * 3600, longTermPoll)
        }
    }
}

def chargingPoll() {
    log.info "🔋 Charging poll for ${device.label} (monitoring charge progress)"
    
    try {
        // Poll the vehicle directly for fresh data to check charging progress
        parent.refreshVehicleStatus(device, true)
        // Note: The charging status check and next poll scheduling happens in updateVehicleStatus()
    } catch (Exception e) {
        log.error "Failed to perform charging poll: ${e.message}"
    }
}

def pollVehicle() {
    log.info "Polling vehicle directly for ${device.label} (this may take 10-30 seconds)"
    
    try {
        logDebug "Calling parent app to poll vehicle status..."
        // This will trigger a refresh from the vehicle (not cached data)
        parent.refreshVehicleStatus(device, true)
        logDebug "Vehicle poll request completed"
    } catch (Exception e) {
        log.error "Failed to poll vehicle: ${e.message}"
        sendEvent(name: "LastRefreshTime", value: "Failed: " + new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

// ====================
// VEHICLE COMMANDS
// ====================

def Lock() {
    log.info "Locking ${device.label}..."
    parent.sendVehicleCommand(device, "lock")
}

def Unlock() {
    log.info "Unlocking ${device.label}..."
    parent.sendVehicleCommand(device, "unlock")
}

def StartClimate() {
    def temp = climateTemp ?: 72
    log.info "Starting climate control for ${device.label} at ${temp}°F..."
    parent.sendVehicleCommand(device, "start", [temperature: temp])
}

def StopClimate() {
    log.info "Stopping climate control for ${device.label}..."
    parent.sendVehicleCommand(device, "stop")
}

def HornAndLights() {
    log.info "Activating horn and lights for ${device.label}..."
    parent.sendVehicleCommand(device, "horn_lights")
}

def GetLocation() {
    log.info "Getting location for ${device.label}..."
    parent.sendVehicleCommand(device, "location")
}

// ====================
// STANDARD CAPABILITY COMMANDS
// ====================

// Switch capability (for climate control)
def on() {
    StartClimate()
}

def off() {
    StopClimate()
}

// Lock capability
def lock() {
    Lock()
}

def unlock() {
    Unlock()
}

// ThermostatSetpoint capability command (for climate temperature control)
def setThermostatSetpoint(temperature) {
    log.info "Setting climate temperature to ${temperature}°F for ${device.label}"
    
    // Update the climate temperature setting
    device.updateSetting("climateTemp", temperature as Integer)
    
    // If climate is currently on, restart it with new temperature
    def currentSwitch = device.currentValue("switch")
    if (currentSwitch == "on") {
        log.info "Climate is on, restarting with new temperature: ${temperature}°F"
        StartClimate()
    }
    
    // Update the thermostat setpoint attributes
    sendEvent(name: "thermostatSetpoint", value: temperature)
    sendEvent(name: "coolingSetpoint", value: temperature)
    sendEvent(name: "heatingSetpoint", value: temperature)
}

// ThermostatCoolingSetpoint capability command
def setCoolingSetpoint(temperature) {
    log.info "Setting cooling setpoint to ${temperature}°F (delegates to setThermostatSetpoint)"
    setThermostatSetpoint(temperature)
}

// ThermostatHeatingSetpoint capability command
def setHeatingSetpoint(temperature) {
    log.info "Setting heating setpoint to ${temperature}°F (delegates to setThermostatSetpoint)"
    setThermostatSetpoint(temperature)
}

// ====================
// STATUS UPDATES
// ====================

def updateVehicleStatus(Map statusData) {
    if (debugLogging) log.debug "Updating vehicle status with ${statusData?.size()} attributes"
    
    if (statusData) {
        statusData.each { key, value ->
            if (value != null) {
                // Special handling for attributes that need unit parameter
                if (key == "AuxBattery" && statusData.containsKey("AuxBatteryUnit")) {
                    def numValue = value
                    def unit = statusData["AuxBatteryUnit"]
                    sendEvent(name: key, value: numValue, unit: unit)
                } else if (key == "AirTemp" && statusData.containsKey("AirTempUnit")) {
                    def numValue = value
                    def unit = statusData["AirTempUnit"]
                    sendEvent(name: key, value: numValue, unit: unit)
                } else if (key == "ChargingPower" && statusData.containsKey("ChargingPowerUnit")) {
                    def numValue = value
                    def unit = statusData["ChargingPowerUnit"]
                    sendEvent(name: key, value: numValue, unit: unit)
                } else if (key == "Speed" && statusData.containsKey("SpeedUnit")) {
                    def numValue = value
                    def unit = statusData["SpeedUnit"]
                    sendEvent(name: key, value: numValue, unit: unit)
                } else if (key == "Altitude" && statusData.containsKey("AltitudeUnit")) {
                    def numValue = value
                    def unit = statusData["AltitudeUnit"]
                    sendEvent(name: key, value: numValue, unit: unit)
                } else if (key.endsWith("Unit")) {
                    // Skip unit keys as they're handled above
                    return
                } else {
                    // Map to standard capability attributes
                    if (key == "BatterySoC") {
                        sendEvent(name: "battery", value: value)
                    } else if (key == "AirControl") {
                        sendEvent(name: "switch", value: value == "On" ? "on" : "off")
                        sendEvent(name: "thermostatMode", value: value == "On" ? "auto" : "off")
                    } else if (key == "DoorLocks") {
                        sendEvent(name: "lock", value: value == "Locked" ? "locked" : "unlocked")
                    } else if (key == "isHome") {
                        sendEvent(name: "presence", value: value == "true" ? "present" : "not present")
                    }
                    
                    // Send the standard attribute event
                    sendEvent(name: key, value: value)
                    
                    // Update contact sensor if any door/window status changed
                    if (key in ["FrontLeftDoor", "FrontRightDoor", "BackLeftDoor", "BackRightDoor", "Hood", "Trunk", "Windows"]) {
                        updateContactSensorStatus()
                    }
                }
            }
        }
        
        // After all attributes are updated, publish battery to MQTT if conditions are met
        if (statusData.containsKey("BatterySoC")) {
            publishBatteryToMqtt(statusData)
        }
        
        // Update the HTML status displays
        updateStatusHtml(statusData)
        
        // Handle smart polling schedule based on charging status
        handleSmartPolling()
    }
}

def handleSmartPolling() {
    try {
        // Check if vehicle is actively charging
        def chargingStatus = device.currentValue("ChargingStatus")
        def isCharging = (chargingStatus?.toLowerCase() == "charging")
        
        // Cancel any existing charging polls
        unschedule("chargingPoll")
        
        if (isCharging && chargingPollingMinutes && chargingPollingMinutes > 0) {
            // Vehicle is charging - schedule frequent polling
            runIn(chargingPollingMinutes * 60, chargingPoll)
            log.info "🔋 Vehicle is charging - next poll in ${chargingPollingMinutes} minutes"
        } else {
            // Vehicle is not charging - rely on long-term polling only
            if (debugLogging) log.debug "🕒 Vehicle not charging - using long-term polling schedule only"
        }
        
    } catch (Exception e) {
        log.error "Failed to handle smart polling: ${e.message}"
    }
}

def updateStatusHtml(Map statusData) {
    def deviceName = device.getDisplayName()
    
    // ============================================================================
    // Vehicle Info HTML (uses CSS classes defined at dashboard level)
    // ============================================================================
    def model = device.currentValue("Model") ?: ""
    def modelYear = device.currentValue("ModelYear") ?: ""
    def modelDisplay = [model, modelYear].findAll().join(" ")
    
    def vehicleInfoHtml = """<div class="kia-status"><h3>${deviceName}</h3><table>
<tr><td class="kia-label">Model</td><td>${modelDisplay}</td></tr>
<tr><td class="kia-label">Odometer</td><td>${device.currentValue("Odometer")} mi</td></tr>
<tr><td class="kia-label">Updated</td><td>${device.currentValue("LastRefreshTime")}</td></tr>
</table></div>""".replaceAll(/\n/, '')
    sendEvent(name: "vehicleInfoHtml", value: vehicleInfoHtml)
    
    
    // ============================================================================
    // Battery HTML (uses CSS classes defined at dashboard level)
    // ============================================================================
    if (device.currentValue("isEV") == "true") {
        def batterySoC = device.currentValue("BatterySoC") ?: "?"
        def evRange = device.currentValue("EVRange") ?: "?"
        def auxBattery = device.currentValue("AuxBattery") ?: "?"
        
        def batteryHtml = """<div class="kia-status"><h3>🔋 Battery</h3><table>
<tr><td class="kia-label">Main Battery</td><td>${batterySoC}%</td></tr>
<tr><td class="kia-label">EV Range</td><td>${evRange} mi</td></tr>
<tr><td class="kia-label">12V Battery</td><td>${auxBattery}</td></tr>
</table></div>""".replaceAll(/\n/, '')
        sendEvent(name: "batteryHtml", value: batteryHtml)
        
        // ============================================================================
        // Charging HTML (uses CSS classes defined at dashboard level)
        // ============================================================================
        def chargingStatus = device.currentValue("ChargingStatus") ?: "?"
        def chargingPower = device.currentValue("ChargingPower")
        def chargingPowerDisplay = chargingPower ? "${chargingPower} kW" : "N/A"
        def plugStatus = device.currentValue("PlugStatus") ?: "?"
        def chargeTimeRemaining = device.currentValue("ChargeTimeRemaining") ?: "?"
        def estimatedCompletionTimeRaw = device.currentValue("EstimatedChargeCompletionTime")
        def estimatedCompletionTime = "?"
        
        if (estimatedCompletionTimeRaw && estimatedCompletionTimeRaw != "Unknown") {
            try {
                def completionDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", estimatedCompletionTimeRaw)
                def now = new Date()
                def daysDiff = (completionDate.time - now.time) / (1000 * 60 * 60 * 24) as int
                
                if (daysDiff == 0) {
                    estimatedCompletionTime = completionDate.format("h:mm a")
                } else if (daysDiff == 1) {
                    estimatedCompletionTime = "Tomorrow " + completionDate.format("h:mm a")
                } else if (daysDiff <= 7) {
                    estimatedCompletionTime = completionDate.format("EEE h:mm a")
                } else {
                    estimatedCompletionTime = completionDate.format("MMM d h:mm a")
                }
            } catch (Exception e) {
                estimatedCompletionTime = estimatedCompletionTimeRaw
            }
        }
        
        def chargingHtml = """<div class="kia-status"><h3>⚡ Charging</h3><table>
<tr><td class="kia-label">Status</td><td>${chargingStatus}</td></tr>
<tr><td class="kia-label">Plug Status</td><td>${plugStatus}</td></tr>
<tr><td class="kia-label">Charging Power</td><td>${chargingPowerDisplay}</td></tr>
<tr><td class="kia-label">Time Remaining</td><td>${chargeTimeRemaining}</td></tr>
<tr><td class="kia-label">Estimated Done</td><td>${estimatedCompletionTime}</td></tr>
</table></div>""".replaceAll(/\n/, '')
        sendEvent(name: "chargingHtml", value: chargingHtml)
    } else {
        // For non-EV vehicles
        def fuelLevel = device.currentValue("FuelLevel") ?: "?"
        def fuelRange = device.currentValue("FuelRange") ?: "?"
        def auxBattery = device.currentValue("AuxBattery") ?: "?"
        
        def batteryHtml = """<div class="kia-status"><h3>⛽ Fuel</h3><table>
<tr><td class="kia-label">Fuel Level</td><td>${fuelLevel}%</td></tr>
<tr><td class="kia-label">Fuel Range</td><td>${fuelRange} mi</td></tr>
<tr><td class="kia-label">12V Battery</td><td>${auxBattery}</td></tr>
</table></div>""".replaceAll(/\n/, '')
        sendEvent(name: "batteryHtml", value: batteryHtml)
        sendEvent(name: "chargingHtml", value: "")
    }
    
    // ============================================================================
    // Doors & Security HTML (uses CSS classes defined at dashboard level)
    // ============================================================================
    def doorLocks = device.currentValue("DoorLocks") ?: "?"
    def frontLeftDoor = device.currentValue("FrontLeftDoor") ?: "?"
    def frontRightDoor = device.currentValue("FrontRightDoor") ?: "?"
    def backLeftDoor = device.currentValue("BackLeftDoor") ?: "?"
    def backRightDoor = device.currentValue("BackRightDoor") ?: "?"
    def hood = device.currentValue("Hood") ?: "?"
    def trunk = device.currentValue("Trunk") ?: "?"
    def windows = device.currentValue("Windows") ?: "?"
    def engine = device.currentValue("Engine") ?: "?"
    def airControl = device.currentValue("AirControl") ?: "?"
    
    def doorsSecurityHtml = """<div class="kia-status"><h3>🚗 Doors & Security</h3><table>
<tr><td class="kia-label">Door Locks</td><td>${doorLocks}</td></tr>
<tr><td class="kia-label">Front Left</td><td>${frontLeftDoor}</td></tr>
<tr><td class="kia-label">Front Right</td><td>${frontRightDoor}</td></tr>
<tr><td class="kia-label">Back Left</td><td>${backLeftDoor}</td></tr>
<tr><td class="kia-label">Back Right</td><td>${backRightDoor}</td></tr>
<tr><td class="kia-label">Hood</td><td>${hood}</td></tr>
<tr><td class="kia-label">Trunk</td><td>${trunk}</td></tr>
<tr><td class="kia-label">Windows</td><td>${windows}</td></tr>
<tr><td class="kia-label">Engine</td><td>${engine}</td></tr>
<tr><td class="kia-label">Climate</td><td>${airControl}</td></tr>
</table></div>""".replaceAll(/\n/, '')
    sendEvent(name: "doorsSecurityHtml", value: doorsSecurityHtml)
    
    // ============================================================================
    // Location HTML (uses CSS classes defined at dashboard level)
    // ============================================================================
    def latitude = device.currentValue("Latitude")
    def longitude = device.currentValue("Longitude")
    def googleMapsUrl = device.currentValue("GoogleMapsURL")
    
    if (latitude && longitude && googleMapsUrl) {
        def isHome = device.currentValue("isHome")
        def homeIcon = isHome == "true" ? "🏠" : "🚗"
        def homeStatus = isHome == "true" ? "At Home" : "Away"
        def homeClass = isHome == "true" ? "kia-home" : "kia-away"
        def speed = device.currentValue("Speed")
        def heading = device.currentValue("Heading")
        def altitude = device.currentValue("Altitude")
        
        def locationRows = ""
        locationRows += "<tr><td class='kia-label'>Coordinates</td><td><a href='${googleMapsUrl}' target='_blank' class='kia-link'>${latitude}, ${longitude}</a></td></tr>"
        locationRows += "<tr><td class='kia-label'>Location Status</td><td class='${homeClass}'>${homeIcon} ${homeStatus}</td></tr>"
        
        if (speed && speed != "null" && speed != 0) {
            locationRows += "<tr><td class='kia-label'>Speed</td><td>${speed} mph</td></tr>"
        }
        if (heading && heading != "null") {
            locationRows += "<tr><td class='kia-label'>Heading</td><td>${heading}°</td></tr>"
        }
        if (altitude && altitude != "null") {
            locationRows += "<tr><td class='kia-label'>Altitude</td><td>${altitude} m</td></tr>"
        }
        
        def locationHtml = """<div class="kia-status"><h3>📍 Location</h3><table>${locationRows}</table></div>""".replaceAll(/\n/, '')
        sendEvent(name: "locationHtml", value: locationHtml)
    } else {
        def locationHtml = "<div class='kia-status'><h3>📍 Location</h3><p>No location data available</p></div>"
        sendEvent(name: "locationHtml", value: locationHtml)
    }
}

def updateLocation(latitude, longitude) {
    try {
        sendEvent(name: "Latitude", value: latitude)
        sendEvent(name: "Longitude", value: longitude)
        sendEvent(name: "Location", value: "${latitude}, ${longitude}")
        
        if (debugLogging) log.debug "Location updated: ${latitude}, ${longitude}"
    } catch (Exception e) {
        log.error "Failed to update location: ${e.message}"
    }
}

def updateContactSensorStatus() {
    try {
        def isAnyOpen = false
        
        // Check individual door status (physical open/closed)
        def frontLeftDoor = device.currentValue("FrontLeftDoor")
        if (frontLeftDoor?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def frontRightDoor = device.currentValue("FrontRightDoor")
        if (frontRightDoor?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def backLeftDoor = device.currentValue("BackLeftDoor")
        if (backLeftDoor?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def backRightDoor = device.currentValue("BackRightDoor")
        if (backRightDoor?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def hood = device.currentValue("Hood")
        if (hood?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def trunk = device.currentValue("Trunk")
        if (trunk?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        def windows = device.currentValue("Windows")
        if (windows?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        // Set contact sensor status
        sendEvent(name: "contact", value: isAnyOpen ? "open" : "closed")
        
        if (debugLogging) log.debug "Contact sensor status updated: ${isAnyOpen ? 'open' : 'closed'}"
    } catch (Exception e) {
        log.error "Failed to update contact sensor status: ${e.message}"
    }
}

def publishBatteryToMqtt(Map statusData = null) {
    if (!enableMqtt || !mqttBroker || !mqttTopic) {
        if (debugLogging) log.debug "MQTT not enabled or not fully configured"
        return
    }
    
    try {
        // Use statusData if provided, otherwise fall back to device.currentValue
        def isHome = statusData?.isHome ?: device.currentValue("isHome")
        def presence = statusData?.presence ?: device.currentValue("presence")
        def plugStatus = statusData?.PlugStatus ?: device.currentValue("PlugStatus")
        def batterySoC = statusData?.BatterySoC ?: device.currentValue("BatterySoC")
        
        // Check if vehicle is at home (via isHome OR presence)
        def atHome = (isHome == "true") || (presence == "present")
        
        // Check if vehicle is plugged in
        def isPluggedIn = (plugStatus?.toLowerCase()?.contains("connected") || plugStatus?.toLowerCase() == "plugged in")
        
        if (debugLogging) {
            log.debug "MQTT publish conditions: atHome=${atHome}, isPluggedIn=${isPluggedIn}, plugStatus=${plugStatus}, batterySoC=${batterySoC}"
        }
        
        if (atHome && isPluggedIn && batterySoC != null) {
            def batteryValue = batterySoC
            
            // Check if it's an HTTP webhook instead of MQTT
            if (mqttBroker.startsWith("http://") || mqttBroker.startsWith("https://")) {
                // HTTP webhook mode
                def params = [
                    uri: mqttBroker,
                    contentType: "application/json",
                    body: [battery_soc: batteryValue]
                ]
                
                httpPost(params) { response ->
                    log.info "🔋 HTTP Webhook: Published battery SoC = ${batteryValue}% (vehicle at home and plugged in)"
                }
            } else {
                // Standard MQTT mode
                if (!interfaces.mqtt.isConnected()) {
                    // Connect to MQTT broker
                    def brokerParts = mqttBroker.split(":")
                    def broker = brokerParts[0]
                    def port = brokerParts.size() > 1 ? brokerParts[1].toInteger() : 1883
                    
                    def clientId = "hubitat-kia-${device.id}"
                    
                    if (mqttUsername && mqttPassword) {
                        interfaces.mqtt.connect(
                            "tcp://${broker}:${port}",
                            clientId,
                            mqttUsername,
                            mqttPassword
                        )
                    } else {
                        interfaces.mqtt.connect(
                            "tcp://${broker}:${port}",
                            clientId,
                            null,
                            null
                        )
                    }
                    
                    // Wait a moment for connection to establish
                    pauseExecution(1000)
                }
                
                if (interfaces.mqtt.isConnected()) {
                    // Publish battery SoC
                    interfaces.mqtt.publish(mqttTopic, batteryValue.toString(), 1, true)
                    log.info "🔋 MQTT: Published to ${mqttTopic} = ${batteryValue}% (vehicle at home and plugged in)"
                    
                    // Send event for Rule Machine integration
                    sendEvent(name: "mqttBatteryPublish", value: "Published: ${batteryValue}% at ${new Date().format('HH:mm:ss')}")
                } else {
                    log.warn "MQTT not connected, skipping battery publish"
                }
            }
        } else {
            if (debugLogging) {
                def reasons = []
                if (!atHome) reasons.add("not at home")
                if (!isPluggedIn) reasons.add("not plugged in (${plugStatus})")
                if (batterySoC == null) reasons.add("battery SoC unavailable")
                log.debug "🔋 MQTT: Not publishing - ${reasons.join(', ')}"
            }
        }
    } catch (Exception e) {
        log.error "Failed to publish battery to MQTT: ${e.message}"
    }
}

// Handle MQTT messages and status
def parse(String description) {
    // This method handles both general parsing and MQTT messages
    if (debugLogging) log.debug "parse() called with: ${description}"
    
    // Check if this is an MQTT message
    if (description?.startsWith("MQTT:")) {
        def message = interfaces.mqtt.parseMessage(description)
        if (debugLogging) log.debug "MQTT message received: ${message.topic} = ${message.payload}"
        // Handle incoming MQTT messages if needed
    }
}

def mqttClientStatus(String status) {
    if (debugLogging) log.debug "MQTT Status: ${status}"
    
    if (status.startsWith("Connection succeeded")) {
        log.info "✅ MQTT connected to ${mqttBroker}"
    } else if (status.startsWith("Connection lost") || status.startsWith("Connection failed")) {
        log.warn "MQTT connection issue: ${status}"
    }
}
