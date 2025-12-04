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
        capability "Lock"
        capability "PresenceSensor"
        capability "ContactSensor"

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
        
        // Climate Control - temperature tracking (set via child device)
        attribute "climateTemperature", "number"  // Parsed numeric temperature

        // Technical
        attribute "vehicleKey", "string"
        // HTML Status Display (split into sections for dashboard tile 1024 char limit)
        attribute "vehicleInfoHtml", "string"
        attribute "batteryHtml", "string"
        attribute "chargingHtml", "string"
        attribute "doorsSecurityHtml", "string"
        attribute "locationHtml", "string"
        attribute "error", "string"
        attribute "mqttBatteryPublish", "string"

        // Commands
        command "refresh"
        command "pollVehicle"
        command "Lock"
        command "Unlock" 
        command "StartClimate"
        command "StopClimate"
        command "GetLocation"
        command "HornAndLights"
        command "StopHornAndLights"
        command "StartCharge"
        command "StopCharge"
        command "updateVehicleStatus", [[name: "statusData", type: "JSON_OBJECT"]]
        command "updateLocation", [[name: "latitude", type: "STRING"], [name: "longitude", type: "STRING"]]
    }

    preferences {
        // Smart polling configuration
        input name: "longTermPollingHours", type: "number", title: "Long-term polling interval (hours)", description: "Background polling when not charging (0 to disable)", defaultValue: 6, range: "0..72"
        input name: "chargingPollingMinutes", type: "number", title: "Charging polling interval (minutes)", description: "Frequent polling when actively charging", defaultValue: 5, range: "1..60"
        
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        
        // Auto-refresh after commands
        input name: "autoRefreshAfterCommands", type: "bool", title: "Auto-refresh vehicle status after commands", defaultValue: true
        input name: "refreshDelaySeconds", type: "number", title: "Time to wait after sending command before auto-refresh (seconds)", defaultValue: 30, range: "10..120", description: "Climate commands typically take 30-60 seconds for vehicle to respond"
        
        // Climate control settings
        input name: "useDetailedClimate", type: "bool", title: "Enable heated/cooled seat control", description: "Turn OFF if your vehicle doesn't support heated/cooled seats via API (e.g., EV6 2022). Note: Heated steering wheel and defrost work on all vehicles.", defaultValue: false
        input name: "climateTemp", type: "number", title: "Climate temperature (°F)", defaultValue: 72, range: "60..85"
        input name: "climateDuration", type: "number", title: "Climate duration (minutes)", defaultValue: 10, range: "1..30"
        input name: "climateDefrost", type: "bool", title: "Enable defrost", defaultValue: false
        input name: "climateHeatedSteering", type: "bool", title: "Enable heated steering wheel", defaultValue: false
        input name: "climateHeatedSeats", type: "bool", title: "Enable heated seats", defaultValue: false
        input name: "climateCooledSeats", type: "bool", title: "Enable cooled seats", defaultValue: false
        
        // Home location detection
        input name: "homeLatitude", type: "text", title: "Home latitude", description: "Decimal degrees (e.g., 40.712776)", required: false
        input name: "homeLongitude", type: "text", title: "Home longitude", description: "Decimal degrees (e.g., -74.006021)", required: false
        input name: "homeRadius", type: "number", title: "Home radius (meters)", defaultValue: 100, range: "10..1000", description: "Distance from home to consider 'at home'"
        
        // MQTT Configuration
        input name: "enableMqtt", type: "bool", title: "Enable MQTT Battery Publishing", description: "Publish battery SoC to MQTT when home and plugged in", defaultValue: false
        input name: "mqttBroker", type: "text", title: "MQTT Broker/Webhook URL", description: "IP address (192.168.1.100) or HTTP webhook URL for MQTT publishing", required: false
        input name: "mqttPort", type: "number", title: "MQTT Port", description: "MQTT broker port (ignored for HTTP webhooks)", defaultValue: 1883, required: false
        input name: "mqttTopic", type: "text", title: "MQTT Topic", description: "Topic to publish battery SoC (e.g., homeassistant/sensor/kia_battery/state)", required: false
        input name: "mqttUsername", type: "text", title: "MQTT Username", description: "MQTT broker username or HTTP auth (optional)", required: false
        input name: "mqttPassword", type: "password", title: "MQTT Password", description: "MQTT broker password or HTTP auth (optional)", required: false
    }
}

def installed() {
    log.info "Kia UVO Vehicle Driver installed for ${device.label}"
    initialize()
}

def updated() {
    log.info "Kia UVO Vehicle Driver updated for ${device.label}"
    
    // Disconnect from MQTT if connected (will reconnect when needed)
    if (interfaces.mqtt.isConnected()) {
        interfaces.mqtt.disconnect()
        log.info "Disconnected from MQTT broker for reconfiguration"
    }
    
    // Create/update child devices based on current preferences
    createClimateChildDevices()
    
    initialize()
}

// ====================
// CHILD DEVICE MANAGEMENT
// ====================

def createClimateChildDevices() {
    def vehicleId = device.deviceNetworkId
    
    // Create climate on/off switch as a component
    createClimateSwitch("${vehicleId}-climate-switch", "Climate Control")
    
    // Create climate temperature variable as a component
    createClimateVariable("${vehicleId}-climate-temp", "Climate Temperature")
    
    // Create defrost and steering controls
    createChildSwitch("${vehicleId}-defrost-front", "Front Defrost")
    createChildSwitch("${vehicleId}-defrost-rear", "Rear Defrost")
    createSteeringWheelControl("${vehicleId}-steering")
    
    // Create seat controls only if detailed climate is enabled
    def hasSeats = device.getSetting("useDetailedClimate")
    if (hasSeats) {
        createSeatControl("${vehicleId}-seat-driver", "Driver Seat", true)
        createSeatControl("${vehicleId}-seat-passenger", "Passenger Seat", true)
        createSeatControl("${vehicleId}-seat-rear-left", "Rear Left Seat", false)
        createSeatControl("${vehicleId}-seat-rear-right", "Rear Right Seat", false)
    } else {
        // Remove seat controls if detailed climate disabled
        removeSeatControls()
    }
}

def createClimateSwitch(dni, label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("hubitat", "Generic Component Switch", dni, 
                [name: "Generic Component Switch", label: "${device.label} - ${label}", isComponent: true])
            log.info "Created climate switch child device: ${label}"
        } catch (Exception e) {
            log.error "Failed to create climate switch ${label}: ${e.message}"
            return null
        }
    }
    
    // Initialize or fix switch state if not properly set
    if (child) {
        def currentState = child.currentValue("switch")
        if (!currentState || currentState == "unknown") {
            // Use sendEvent() to properly set component device state
            child.sendEvent(name: "switch", value: "off")
            log.info "Initialized climate switch to OFF (was: ${currentState})"
        }
    }
    
    return child
}

def createClimateVariable(dni, label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("kia-uvo", "Generic Variable Child", dni, 
                [name: "Generic Variable Child", label: "${device.label} - ${label}", isComponent: true])
            
            // Set initial value from parent's climateTemperature or default
            def initialTemp = device.currentValue("climateTemperature")?.toString() ?: "72"
            child.setVariable(initialTemp)
            
            log.info "Created climate variable child device: ${label}"
        } catch (Exception e) {
            log.error "Failed to create climate variable ${label}: ${e.message}"
        }
    }
    return child
}

def createChildSwitch(dni, label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("hubitat", "Virtual Switch", dni, 
                [name: "Virtual Switch", label: "${device.label} - ${label}", isComponent: false])
            child.off()
            log.info "Created child device: ${label}"
        } catch (Exception e) {
            log.error "Failed to create child device ${label}: ${e.message}"
        }
    }
    return child
}

def createSteeringWheelControl(dni) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("hubitat", "Virtual Fan Controller", dni,
                [name: "Virtual Fan Controller", label: "${device.label} - Heated Steering Wheel", isComponent: false])
            child.setSpeed("off")
            log.info "Created steering wheel control"
        } catch (Exception e) {
            log.error "Failed to create steering wheel control: ${e.message}"
        }
    }
    return child
}


def createSeatControl(dni, label, supportsCooling) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("kia-uvo", "Kia Climate Seat Control", dni,
                [name: "Kia Climate Seat Control", label: "${device.label} - ${label}", isComponent: false])
            child.off()
            log.info "Created seat control: ${label}"
        } catch (Exception e) {
            log.error "Failed to create seat control ${label}: ${e.message}"
            return null
        }
    }
    
    // Set supported modes and speeds (do this every time to ensure they're set)
    if (child) {
        def supportedModes = supportsCooling ? ["off", "heat", "cool"] : ["off", "heat"]
        def supportedSpeeds = ["low", "medium", "high"]
        
        child.sendEvent(name: "supportedThermostatModes", value: supportedModes)
        child.sendEvent(name: "supportedFanSpeeds", value: supportedSpeeds)
        log.debug "Set ${label} supported modes: ${supportedModes}, speeds: ${supportedSpeeds}"
    }
    
    return child
}

def removeSeatControls() {
    def vehicleId = device.deviceNetworkId
    def seatDnis = [
        "${vehicleId}-seat-driver",
        "${vehicleId}-seat-passenger", 
        "${vehicleId}-seat-rear-left",
        "${vehicleId}-seat-rear-right"
    ]
    
    seatDnis.each { dni ->
        def child = getChildDevice(dni)
        if (child) {
            deleteChildDevice(dni)
            log.info "Removed seat control: ${child.label}"
        }
    }
}

def removeAllClimateChildDevices() {
    def children = getChildDevices()
    children.each { child ->
        if (child.deviceNetworkId.startsWith(device.deviceNetworkId + "-")) {
            deleteChildDevice(child.deviceNetworkId)
            log.info "Removed child device: ${child.label}"
        }
    }
}

// ====================
// INITIALIZATION
// ====================

def initialize() {
    log.info "Initializing Kia UVO Vehicle Driver for ${device.label}"
    
    // Clear all existing schedules
    unschedule()
    
    // Initialize climate temperature (default 72°F)
    if (!device.currentValue("climateTemperature")) {
        sendEvent(name: "climateTemperature", value: 72)
    }
    
    // Create climate child devices
    createClimateChildDevices()
    
    // Schedule long-term polling if enabled
    if (longTermPollingHours && longTermPollingHours > 0) {
        runIn(longTermPollingHours * 3600, longTermPoll)
        log.info "Long-term polling scheduled every ${longTermPollingHours} hours"
    }
    
    // Delay initial status refresh to allow attributes to be set
    runIn(5, refresh)
}

def refresh() {
    log.info "Refreshing cached status for ${device.label} (fast)"
    
    try {
        log.debug "Calling parent app to get cached vehicle status..."
        // Request cached status from parent app (LastRefreshTime will be updated only on successful API response)
        parent.getVehicleStatus(device)
        log.debug "Cached status request completed"
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
        log.debug "Calling parent app to poll vehicle status..."
        // Request fresh status from parent app (polls vehicle directly)
        parent.refreshVehicleStatus(device)
        log.debug "Vehicle poll request completed"
    } catch (Exception e) {
        log.error "Failed to call parent.refreshVehicleStatus: ${e.message}"
        // Set a failure indicator instead of success time
        sendEvent(name: "LastRefreshTime", value: "Failed: " + new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

def parse(String description) {
    if (debugLogging) log.debug "Parsing: ${description}"
    
    // Handle MQTT messages if this is an MQTT message
    if (description.contains("mqtt")) {
        if (debugLogging) log.debug "MQTT message received: ${description}"
    }
}

// ====================
// VEHICLE COMMANDS
// ====================

def Lock() {
    log.info "Locking ${device.label}"
    parent.sendVehicleCommand(device, "lock")
}

def Unlock() {
    log.info "Unlocking ${device.label}"
    parent.sendVehicleCommand(device, "unlock")
}

def StartClimate() {
    def temp = device.currentValue("thermostatSetpoint") ?: device.getSetting("climateTemp") ?: 72
    def duration = device.getSetting("climateDuration") ?: 10
    def vehicleId = device.deviceNetworkId
    
    // Read defrost switches
    def frontDefrost = getChildDevice("${vehicleId}-defrost-front")?.currentValue("switch") == "on"
    def rearDefrost = getChildDevice("${vehicleId}-defrost-rear")?.currentValue("switch") == "on"
    def defrost = frontDefrost || rearDefrost
    
    // Read steering wheel fan speed and map to vehicle capabilities
    def steeringChild = getChildDevice("${vehicleId}-steering")
    def rawSpeed = steeringChild?.currentValue("speed") ?: "off"
    def heatedSteering = mapSteeringWheelSpeed(rawSpeed)
    
    // Build seat settings from child devices
    def seatSettings = [:]
    if (device.getSetting("useDetailedClimate")) {
        seatSettings.driver = getSeatSettingFromChild("${vehicleId}-seat-driver")
        seatSettings.passenger = getSeatSettingFromChild("${vehicleId}-seat-passenger")
        seatSettings.rearLeft = getSeatSettingFromChild("${vehicleId}-seat-rear-left")
        seatSettings.rearRight = getSeatSettingFromChild("${vehicleId}-seat-rear-right")
    }
    
    log.info "Starting climate: ${temp}°F, ${duration}min, defrost:${defrost}, steering:${heatedSteering} (raw: ${rawSpeed})"
    log.debug "Seat settings: ${seatSettings}"
    
    parent.sendVehicleCommand(device, "start", [
        temperature: temp,
        duration: duration,
        defrost: defrost,
        heatedSteering: heatedSteering,
        seats: seatSettings
    ])
}

def mapSteeringWheelSpeed(speed) {
    // Map dashboard fan speeds to vehicle capabilities
    def modelYear = device.currentValue("ModelYear")?.toInteger() ?: 2022
    def supportsLevels = (modelYear >= 2024) // 2024+ supports off/low/high
    
    if (speed == "off") {
        return "off"
    }
    
    if (!supportsLevels) {
        // 2022-2023: Only supports on/off - map everything non-off to "on"
        return "on"
    }
    
    // 2024+: Map to low/high based on user selection
    switch(speed) {
        case "low":
        case "medium-low":
            return "low"
        case "medium":
        case "medium-high":
        case "high":
        case "auto":
            return "high"
        case "on":
            return "high" // Default "on" to high for 2024+ models
        default:
            return "off"
    }
}


def getSeatSettingFromChild(dni) {
    def child = getChildDevice(dni)
    if (!child) return [mode: "off", level: "off"]
    
    def mode = child.currentValue("thermostatMode") ?: "off"
    def speed = child.currentValue("speed") ?: "off"
    
    if (mode == "off" || speed == "off") {
        return [mode: "off", level: "off"]
    }
    
    return [mode: mode, level: speed]
}

def StopClimate() {
    log.info "Stopping climate control for ${device.label}"
    parent.sendVehicleCommand(device, "stop")
}

def GetLocation() {
    log.info "Getting location for ${device.label}"
    parent.sendVehicleCommand(device, "location")
}

def HornAndLights() {
    log.info "Activating horn and lights for ${device.label}"
    parent.sendVehicleCommand(device, "horn_lights")
}

def StopHornAndLights() {
    log.info "Stopping horn and lights for ${device.label}"
    parent.sendVehicleCommand(device, "stop_horn_lights")
}

def StartCharge() {
    log.info "Starting charge for ${device.label}"
    parent.sendVehicleCommand(device, "start_charge")
}

def StopCharge() {
    log.info "Stopping charge for ${device.label}"
    parent.sendVehicleCommand(device, "stop_charge")
}

// ====================
// STANDARD CAPABILITY COMMANDS
// ====================

// Climate child device event handler (called when child switch changes)
def componentOn(child) {
    log.info "Climate switch turned ON via child device"
    // Update child device state immediately for UI feedback
    child.sendEvent(name: "switch", value: "on")
    StartClimate()
}

def componentOff(child) {
    log.info "Climate switch turned OFF via child device"
    // Update child device state immediately for UI feedback
    child.sendEvent(name: "switch", value: "off")
    StopClimate()
}

// Climate temperature variable event handler (called when child variable changes)
def componentVariableChanged(child, value) {
    log.info "Climate temperature changed via child device to: ${value}"
    
    // Parse to number and store as climateTemperature
    def temp = value.toString().isNumber() ? value.toString() as Integer : 72
    sendEvent(name: "climateTemperature", value: temp)
    
    // Also update the climate temp preference for StartClimate to use
    device.updateSetting("climateTemp", temp)
    
    log.info "Climate temperature set to ${temp}°F"
}

// Lock capability commands (for door locks)
def lock() {
    log.info "Locking ${device.label} (Lock capability)"
    Lock()
}

def unlock() {
    log.info "Unlocking ${device.label} (Lock capability)"
    Unlock()
}

// ====================
// STATUS UPDATES
// ====================

def updateVehicleStatus(Map statusData) {
    if (debugLogging) log.debug "Updating vehicle status with ${statusData?.size()} attributes"
    
    if (statusData) {
        statusData.each { key, value ->
            if (value != null) {
                try {
                    // Handle numeric attributes with units separately
                    if (key == "AuxBattery") {
                        def numValue = value.toString().isNumber() ? value.toString() as Double : null
                        if (numValue != null) {
                            def unit = statusData["AuxBatteryUnit"] ?: "%"
                            sendEvent(name: key, value: numValue, unit: unit)
                            if (debugLogging) log.debug "Updated ${key} = ${numValue} ${unit}"
                        }
                    }
                    else if (key == "AirTemp") {
                        def numValue = value.toString().isNumber() ? value.toString() as Double : null
                        if (numValue != null) {
                            def unit = statusData["AirTempUnit"] ?: "°F"
                            sendEvent(name: key, value: numValue, unit: unit)
                            if (debugLogging) log.debug "Updated ${key} = ${numValue}${unit}"
                        }
                    }
                    else if (key == "ChargingPower") {
                        def numValue = value.toString().isNumber() ? value.toString() as Double : null
                        if (numValue != null) {
                            def unit = statusData["ChargingPowerUnit"] ?: "kW"
                            sendEvent(name: key, value: numValue, unit: unit)
                            if (debugLogging) log.debug "Updated ${key} = ${numValue} ${unit}"
                        }
                    }
                    else if (key.endsWith("Unit")) {
                        // Skip unit data - it's used with the corresponding value attribute
                        if (debugLogging) log.debug "Skipping unit data: ${key} = ${value}"
                    }
                    else {
                        // Standard string attribute
                        sendEvent(name: key, value: value.toString())
                        if (debugLogging) log.debug "Updated ${key} = ${value}"
                    }
                    
                    // Map custom attributes to standard Hubitat capability attributes
                    if (key == "BatterySoC") {
                        sendEvent(name: 'battery', value: value.toString())
                    }
                    else if (key == "AirControl") {
                        // Update climate child switch state based on actual climate status from vehicle
                        def climateOn = value.toString().toLowerCase().contains("on") || 
                                       value.toString().toLowerCase().contains("running") || 
                                       value.toString().toLowerCase().contains("active")
                        
                        def vehicleId = device.deviceNetworkId
                        def climateSwitch = getChildDevice("${vehicleId}-climate-switch")
                        
                        if (climateSwitch) {
                            def currentSwitch = climateSwitch.currentValue("switch")
                            
                            // If climate is confirmed ON and switch shows OFF, update to ON
                            if (climateOn && currentSwitch == "off") {
                                climateSwitch.sendEvent(name: "switch", value: "on")
                                log.info "Climate confirmed running - switch updated to ON"
                            }
                            // If climate is confirmed OFF and switch shows ON, update to OFF
                            else if (!climateOn && currentSwitch == "on") {
                                climateSwitch.sendEvent(name: "switch", value: "off")
                                log.info "Climate confirmed stopped - switch updated to OFF"
                            }
                            // Otherwise, leave switch state alone (user may have just triggered it)
                        }
                    }
                    else if (key == "AirTemp") {
                        // Map air temperature to climate temperature
                        def numValue = value.toString().isNumber() ? value.toString() as Double : null
                        if (numValue != null) {
                            def intValue = numValue as Integer
                            sendEvent(name: 'climateTemperature', value: intValue)
                            
                            // Also update the child variable device
                            def vehicleId = device.deviceNetworkId
                            def tempChild = getChildDevice("${vehicleId}-climate-temp")
                            if (tempChild) {
                                tempChild.setVariable(intValue.toString())
                            }
                        }
                    }
                    else if (key == "DoorLocks") {
                        // Map door lock status to Lock capability
                        def lockValue = value.toString().toLowerCase().contains("locked") ? "locked" : "unlocked"
                        sendEvent(name: 'lock', value: lockValue)
                    }
                    else if (key == "isHome") {
                        // Map home detection to PresenceSensor capability
                        def presenceValue = value.toString() == "true" ? "present" : "not present"
                        sendEvent(name: 'presence', value: presenceValue)
                    }
                    else if (key == "FrontLeftDoor" || key == "FrontRightDoor" || key == "BackLeftDoor" || key == "BackRightDoor" || key == "Hood" || key == "Trunk" || key == "Windows") {
                        // Update combined contact sensor status when any opening status changes
                        updateContactSensorStatus()
                    }
                    
                    if (debugLogging) log.debug "Updated ${key} = ${value}"
                } catch (Exception e) {
                    log.error "Failed to update ${key}: ${e.message}"
                    sendEvent(name: "error", value: "Attribute Update Failed", descriptionText: "Failed to update ${key}: ${e.message}")
                }
            }
        }
        
        // Update HTML status display
        updateStatusHtml(statusData)
        
        // Smart polling logic - schedule next poll based on charging status
        def newChargingStatus = statusData["ChargingStatus"]
        handleSmartPolling(newChargingStatus)
        
        // Publish battery to MQTT after all attributes have been updated
        // Pass statusData directly to avoid relying on device.currentValue() which may not be atomic
        if (statusData.containsKey("BatterySoC")) {
            publishBatteryToMqtt(statusData)
        }
        
        if (debugLogging) log.debug "Successfully updated vehicle status"
    } else {
        log.warn "statusData is null or empty"
    }
}

def handleSmartPolling(chargingStatus = null) {
    try {
        // Use passed charging status or get current value as fallback
        if (chargingStatus == null) {
            chargingStatus = device.currentValue("ChargingStatus")
        }
        def isCharging = chargingStatus && chargingStatus.toString().toLowerCase() == "charging"
        
        if (debugLogging) log.debug "Smart polling check: ChargingStatus='${chargingStatus}', isCharging=${isCharging}"
        
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
    def modelDisplay = [model, modelYear].findAll().join(" ")  // Only join non-empty values
    
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
        
        // Check hood status
        def hood = device.currentValue("Hood")
        if (hood?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        // Check trunk status
        def trunk = device.currentValue("Trunk")
        if (trunk?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        // Check windows status (if any window is open)
        def windows = device.currentValue("Windows")
        if (windows?.toLowerCase()?.contains("open")) {
            isAnyOpen = true
        }
        
        // Set contact sensor value
        def contactValue = isAnyOpen ? "open" : "closed"
        sendEvent(name: 'contact', value: contactValue)
        
        if (debugLogging) log.debug "Contact sensor updated: ${contactValue} (FL: ${frontLeftDoor}, FR: ${frontRightDoor}, BL: ${backLeftDoor}, BR: ${backRightDoor}, hood: ${hood}, trunk: ${trunk}, windows: ${windows})"
        
    } catch (Exception e) {
        log.error "Failed to update contact sensor status: ${e.message}"
    }
}

// ====================
// MQTT PUBLISHING
// ====================

def publishBatteryToMqtt(Map statusData = null) {
    try {
        // Check if MQTT is enabled
        if (!enableMqtt || !mqttBroker || !mqttTopic) {
            if (debugLogging) log.debug "MQTT not enabled or not configured, skipping publish"
            return
        }
        
        // Use statusData if provided (preferred), otherwise fall back to device.currentValue()
        // This avoids race conditions where sendEvent() hasn't updated currentValue() yet
        def isHome = statusData?.isHome ?: device.currentValue("isHome")
        def presence = statusData?.presence ?: device.currentValue("presence")
        def plugStatus = statusData?.PlugStatus ?: device.currentValue("PlugStatus")
        def batterySoC = statusData?.BatterySoC ?: device.currentValue("BatterySoC")
        
        // Check if vehicle is home - check both isHome and presence attributes
        def atHome = (isHome == "true") || (presence == "present")
        
        if (!atHome) {
            if (debugLogging) log.debug "Vehicle not at home (isHome: ${isHome}, presence: ${presence}), skipping MQTT publish"
            return
        }
        
        // Check if vehicle is plugged in - check for exact status (avoid "unplugged" matching "plugged")
        def isPluggedIn = plugStatus && (plugStatus.toLowerCase().contains("connected") ||
                                        plugStatus.toLowerCase() == "plugged in")
        
        if (!isPluggedIn) {
            if (debugLogging) log.debug "Vehicle not plugged in (PlugStatus: ${plugStatus}), skipping MQTT publish"
            return
        }
        
        // Check battery SoC
        if (!batterySoC) {
            log.warn "No battery SoC available for MQTT publish"
            return
        }
        
        // Clean up battery value (remove % if present)
        def batteryValue = batterySoC.toString().replaceAll("[^0-9.]", "")
        
        // Connect to MQTT broker if not already connected
        if (!interfaces.mqtt.isConnected()) {
            def brokerUrl = mqttBroker.startsWith("tcp://") ? mqttBroker : "tcp://${mqttBroker}:${mqttPort ?: 1883}"
            def clientId = "hubitat_kia_${device.id}_${now()}"
            
            log.info "🔌 Connecting to MQTT broker: ${brokerUrl}"
            interfaces.mqtt.connect(brokerUrl, clientId, mqttUsername, mqttPassword)
            
            // Give it a moment to connect
            pauseExecution(1000)
        }
        
        // Publish the battery SoC
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.publish(mqttTopic, batteryValue, 1, true) // QoS 1, retained
            log.info "🔋 MQTT: Published to ${mqttTopic} = ${batteryValue}% (vehicle at home and plugged in)"
            
            // Send a custom event for Rule Machine integration as well
            sendEvent(name: "mqttBatteryPublish", value: batteryValue, descriptionText: "Battery SoC published to MQTT: ${batteryValue}%")
        } else {
            log.warn "Failed to connect to MQTT broker for publishing"
        }
        
    } catch (Exception e) {
        log.error "Failed to publish battery to MQTT: ${e.message}"
    }
}

// Required method for MQTT interface - handles connection status
def mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
        log.error "MQTT Client Error: ${message}"
    } else {
        if (debugLogging) log.debug "MQTT Client Status: ${message}"
    }
} 