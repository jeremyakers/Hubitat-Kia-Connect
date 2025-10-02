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
        attribute "Speed", "string"
        attribute "Heading", "string"
        attribute "Altitude", "string"
        attribute "isHome", "string"

        // EV Specific (if applicable)
        attribute "isEV", "string"
        attribute "BatterySoC", "string"
        attribute "EVRange", "string"
        attribute "ChargingStatus", "string"
        attribute "PlugStatus", "string"
        attribute "ChargingPower", "string"
        attribute "ChargeTimeRemaining", "string"
        attribute "ChargeTimeRemainingMinutes", "string"
        attribute "ChargeTimeRemainingHours", "string"
        attribute "EstimatedChargeCompletionTime", "string"

        // Fuel (if applicable)
        attribute "FuelLevel", "string"
        attribute "FuelRange", "string"
        
        // 12V Auxiliary Battery
        attribute "AuxBattery", "string"

        // Environmental
        attribute "AirTemp", "string"
        attribute "OutsideTemp", "string"
        attribute "AirControl", "string"

        // Technical
        attribute "vehicleKey", "string"
        attribute "statusHtml", "string"
        attribute "error", "string"

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
        input name: "refreshInterval", type: "number", title: "Auto-refresh interval (minutes)", description: "0 to disable", defaultValue: 30, range: "0..1440"
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        
        // Auto-refresh after commands
        input name: "autoRefreshAfterCommands", type: "bool", title: "Auto-refresh vehicle status after commands", defaultValue: true
        input name: "refreshDelaySeconds", type: "number", title: "Time to wait after sending command before auto-refresh (seconds)", defaultValue: 5, range: "1..30"
        
        // Climate control settings
        input name: "useDetailedClimate", type: "bool", title: "Use detailed climate parameters", defaultValue: false
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
    }
}

def installed() {
    log.info "Kia UVO Vehicle Driver installed for ${device.label}"
    initialize()
}

def updated() {
    log.info "Kia UVO Vehicle Driver updated for ${device.label}"
    initialize()
}

def initialize() {
    log.info "Initializing Kia UVO Vehicle Driver for ${device.label}"
    
    // Schedule auto-refresh if enabled
    unschedule()
    if (refreshInterval && refreshInterval > 0) {
        runIn(refreshInterval * 60, refresh)
        log.debug "Auto-refresh scheduled every ${refreshInterval} minutes"
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
    
    // Schedule next refresh if auto-refresh is enabled
    if (refreshInterval && refreshInterval > 0) {
        runIn(refreshInterval * 60, refresh)
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
    log.info "Starting climate control for ${device.label}"
    parent.sendVehicleCommand(device, "start")
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

// Switch capability commands (for climate control)
def on() {
    log.info "Turning on climate control for ${device.label} (Switch capability)"
    StartClimate()
}

def off() {
    log.info "Turning off climate control for ${device.label} (Switch capability)"
    StopClimate()
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
                    sendEvent(name: key, value: value.toString())
                    
                    // Map custom attributes to standard Hubitat capability attributes
                    if (key == "BatterySoC") {
                        sendEvent(name: 'battery', value: value.toString())
                    }
                    else if (key == "AirControl") {
                        // Map climate control status to Switch capability
                        def switchValue = value.toString().toLowerCase().contains("on") || 
                                         value.toString().toLowerCase().contains("running") || 
                                         value.toString().toLowerCase().contains("active") ? "on" : "off"
                        sendEvent(name: 'switch', value: switchValue)
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
                    else if (key == "DoorLocks" || key == "Hood" || key == "Trunk" || key == "Windows") {
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
        if (debugLogging) log.debug "Successfully updated vehicle status"
    } else {
        log.warn "statusData is null or empty"
    }
}

def updateStatusHtml(Map statusData) {
    def html = """
    <div style="font-family: Arial, sans-serif; font-size: 14px;">
        <h3 style="color: #1f77b4; margin-bottom: 10px;">${device.getDisplayName()}</h3>
        <table style="width: 100%; border-collapse: collapse;">
            <tr><td style="padding: 2px; font-weight: bold;">Model:</td><td style="padding: 2px;">${device.currentValue("Model")} ${device.currentValue("ModelYear")}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Odometer:</td><td style="padding: 2px;">${device.currentValue("Odometer")} miles</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Doors:</td><td style="padding: 2px;">${device.currentValue("DoorLocks")}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Engine:</td><td style="padding: 2px;">${device.currentValue("Engine")}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Air Control:</td><td style="padding: 2px;">${device.currentValue("AirControl") ?: "Unknown"}</td></tr>
    """
    
    // Add EV-specific info if it's an EV
    if (device.currentValue("isEV") == "true") {
        def batterySoC = device.currentValue("BatterySoC") ?: "Unknown"
        def evRange = device.currentValue("EVRange") ?: "Unknown"
        def chargingStatus = device.currentValue("ChargingStatus") ?: "Unknown"
        def chargingPower = device.currentValue("ChargingPower") ?: "Not Available"
        def plugStatus = device.currentValue("PlugStatus") ?: "Unknown"
        def auxBattery = device.currentValue("AuxBattery") ?: "Unknown"
        
        def chargeTimeRemaining = device.currentValue("ChargeTimeRemaining") ?: "Unknown"
        def estimatedCompletionTimeRaw = device.currentValue("EstimatedChargeCompletionTime")
        def estimatedCompletionTime = "Unknown"
        
        // Format the timestamp for display if available
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
        
        html += """
            <tr><td style="padding: 2px; font-weight: bold;">Main Battery:</td><td style="padding: 2px;">${batterySoC}%</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">EV Range:</td><td style="padding: 2px;">${evRange} miles</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Charging:</td><td style="padding: 2px;">${chargingStatus}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Charging Power:</td><td style="padding: 2px;">${chargingPower}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Charge Time Remaining:</td><td style="padding: 2px;">${chargeTimeRemaining}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Estimated Completion:</td><td style="padding: 2px;">${estimatedCompletionTime}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Plug Status:</td><td style="padding: 2px;">${plugStatus}</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">12V Aux Battery:</td><td style="padding: 2px;">${auxBattery}</td></tr>
        """
    } else {
        def fuelLevel = device.currentValue("FuelLevel") ?: "Unknown"
        def fuelRange = device.currentValue("FuelRange") ?: "Unknown"
        def auxBattery = device.currentValue("AuxBattery") ?: "Unknown"
        
        html += """
            <tr><td style="padding: 2px; font-weight: bold;">Fuel:</td><td style="padding: 2px;">${fuelLevel}%</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">Range:</td><td style="padding: 2px;">${fuelRange} miles</td></tr>
            <tr><td style="padding: 2px; font-weight: bold;">12V Battery:</td><td style="padding: 2px;">${auxBattery}</td></tr>
        """
    }
    
    // Add location info if available
    def latitude = device.currentValue("Latitude")
    def longitude = device.currentValue("Longitude")
    def googleMapsUrl = device.currentValue("GoogleMapsURL")
    if (latitude && longitude && googleMapsUrl) {
        html += """
            <tr><td style="padding: 2px; font-weight: bold;">Location:</td><td style="padding: 2px;"><a href="${googleMapsUrl}" target="_blank" style="color: #1f77b4; text-decoration: none;">${latitude}, ${longitude}</a></td></tr>
        """
        
        // Add home status
        def isHome = device.currentValue("isHome")
        if (isHome && isHome != "Unknown") {
            def homeIcon = isHome == "true" ? "🏠" : "🚗"
            def homeStatus = isHome == "true" ? "At Home" : "Away"
            def homeColor = isHome == "true" ? "#28a745" : "#6c757d"
            html += """
            <tr><td style="padding: 2px; font-weight: bold;">Home Status:</td><td style="padding: 2px; color: ${homeColor};">${homeIcon} ${homeStatus}</td></tr>
            """
        }
        
        // Add additional location details if available
        def speed = device.currentValue("Speed")
        def heading = device.currentValue("Heading")
        def altitude = device.currentValue("Altitude")
        
        if (speed && speed != "null") {
            html += """
            <tr><td style="padding: 2px; font-weight: bold;">Speed:</td><td style="padding: 2px;">${speed}</td></tr>
            """
        }
        if (heading && heading != "null") {
            html += """
            <tr><td style="padding: 2px; font-weight: bold;">Heading:</td><td style="padding: 2px;">${heading}°</td></tr>
            """
        }
        if (altitude && altitude != "null") {
            html += """
            <tr><td style="padding: 2px; font-weight: bold;">Altitude:</td><td style="padding: 2px;">${altitude}</td></tr>
            """
        }
    }
    
    html += """
            <tr><td style="padding: 2px; font-weight: bold;">Last Update:</td><td style="padding: 2px;">${device.currentValue("LastRefreshTime")}</td></tr>
        </table>
    </div>
    """
    
    sendEvent(name: "statusHtml", value: html)
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
        
        // Check if doors are unlocked (could indicate doors are open/accessible)
        def doorLocks = device.currentValue("DoorLocks")
        if (doorLocks?.toLowerCase()?.contains("unlocked")) {
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
        
        if (debugLogging) log.debug "Contact sensor updated: ${contactValue} (doors: ${doorLocks}, hood: ${hood}, trunk: ${trunk}, windows: ${windows})"
        
    } catch (Exception e) {
        log.error "Failed to update contact sensor status: ${e.message}"
    }
} 