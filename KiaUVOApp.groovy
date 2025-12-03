/**
 * Kia UVO Connect App
 * 
 * Based on reverse engineering of the hyundai_kia_connect_api python library
 * Uses the actual API endpoints and authentication flow
 * 
 * Features:
 * - Automatic session refresh on expiry
 * - Real-time vehicle status monitoring  
 * - EV-specific attributes (battery, charging, range)
 * - Location tracking
 * - Parameterized API configuration for easy maintenance
 * 
 * Author: Jeremy Akers
 * Version: 1.0.0
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

// ====================
// API CONFIGURATION
// ====================

@Field static final String API_HOST = "api.owners.kia.com"
@Field static final String API_BASE_URL = "https://api.owners.kia.com"
@Field static final String CLIENT_ID = "MWAMOBILE"
@Field static final String CLIENT_SECRET = "98er-w34rf-ibf3-3f6h"
@Field static final String USER_AGENT = "okhttp/4.10.0"
@Field static final String APP_VERSION = "7.15.2"

// API Endpoints
@Field static final String AUTH_ENDPOINT = "/apigw/v1/prof/authUser"
@Field static final String VEHICLE_LIST_ENDPOINT = "/apigw/v1/ownr/gvl"
@Field static final String VEHICLE_INFO_ENDPOINT = "/apigw/v1/cmm/gvi"
@Field static final String VEHICLE_STATUS_FRESH_ENDPOINT = "/apigw/v1/rems/rvs"
@Field static final String LOCK_ENDPOINT = "/apigw/v1/rems/door/lock"
@Field static final String UNLOCK_ENDPOINT = "/apigw/v1/rems/door/unlock"
@Field static final String CLIMATE_START_ENDPOINT = "/apigw/v1/rems/start"
@Field static final String CLIMATE_STOP_ENDPOINT = "/apigw/v1/rems/stop"
@Field static final String CHARGE_START_ENDPOINT = "/apigw/v1/evc/charge"
@Field static final String CHARGE_STOP_ENDPOINT = "/apigw/v1/evc/cancel"
@Field static final String HORN_LIGHTS_ON_ENDPOINT = "/apigw/v1/rems/hrl/on"
@Field static final String HORN_LIGHTS_OFF_ENDPOINT = "/apigw/v1/rems/hrl/off"
@Field static final String LOCATION_ENDPOINT = "/apigw/v1/lbs/loc/get"

definition(
    name: "Kia UVO Connect",
    namespace: "kia-uvo",
    author: "Jeremy Akers",
    description: "Connect to Kia UVO (Connected Services) to monitor and control your Kia vehicle",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/KiaUVOApp.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "vehiclesPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Kia UVO Connect", install: true, uninstall: true) {
        section("About") {
            paragraph "This app connects to Kia UVO (Connected Services) to monitor and control your Kia vehicle through Hubitat."
            paragraph "Supports US Kia vehicles with UVO Connect services."
        }
        
        section("Account Credentials") {
            input(name: "username", type: "text", title: "Kia UVO Username (Email)", description: "Your Kia UVO account email", required: true)
            input(name: "password", type: "password", title: "Kia UVO Password", description: "Your Kia UVO account password", required: true)
            
            input(name: "test_auth", type: "button", title: "Test Authentication", submitOnChange: true, action: "testAuthentication")
        }
        
        section("Vehicle Discovery") {
            if (state.authenticated) {
                            paragraph "✅ Authentication successful! Ready to discover vehicles."
            paragraph "💡 <strong>Safe to use:</strong> This will refresh vehicle keys without recreating devices."
            input(name: "discover_vehicles", type: "button", title: "Discover My Vehicles", submitOnChange: true, action: "discoverVehicles")
            } else {
                paragraph "Please test authentication first."
            }
        }
        
        if (state.vehicles) {
            section("Discovered Vehicles") {
                state.vehicles.each { vehicle ->
                    paragraph "🚗 ${vehicle.nickName} (${vehicle.modelName} ${vehicle.modelYear})\nVIN: ${vehicle.vin}"
                }
                paragraph "💡 <strong>Safe to use:</strong> This will update existing devices with fresh data without breaking automations."
                input(name: "create_devices", type: "button", title: "Create/Update Vehicle Devices", submitOnChange: true, action: "createVehicleDevices")
            }
        }
        
        section("Settings") {
            input(name: "stay_logged_in", type: "bool", title: "Stay logged in", description: "Keep session active for faster responses", defaultValue: true)
            input(name: "debug_logging", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true)
            if (settings.debug_logging) {
                input(name: "debug_auto_disable_minutes", type: "number", title: "Auto-disable debug logging after (minutes)", defaultValue: 10, range: "5..60", description: "Debug logging will automatically turn off after this time")
            }
            paragraph "Note: Climate control settings are now configured individually on each vehicle device"
        }
    }
}

def appButtonHandler(btn) {
    switch(btn) {
        case "test_auth":
            testAuthentication()
            break
        case "discover_vehicles":
            discoverVehicles()
            break
        case "create_devices":
            createVehicleDevices()
            break
    }
}

def installed() {
    log.info "Kia UVO Connect app installed"
    initialize()
}

def updated() {
    log.info "Kia UVO Connect app updated"
    
    // Handle debug logging auto-disable
    if (settings.debug_logging) {
        def autoDisableMinutes = settings.debug_auto_disable_minutes ?: 10
        log.info "🐛 Debug logging enabled - will auto-disable in ${autoDisableMinutes} minutes"
        runIn(autoDisableMinutes * 60, "autoDisableDebugLogging")
        state.debugEnabledAt = now()
    } else {
        // Cancel any pending auto-disable
        unschedule("autoDisableDebugLogging")
        state.remove("debugEnabledAt")
    }
    
    initialize()
}

def initialize() {
    unschedule()
    state.authenticated = false
    state.vehicles = null
}

def autoDisableDebugLogging() {
    def minutes = settings.debug_auto_disable_minutes ?: 10
    log.info "🐛 Auto-disabling debug logging after ${minutes} minutes"
    app.updateSetting("debug_logging", false)
    state.remove("debugEnabledAt")
    sendErrorEvent("Debug logging auto-disabled", "Debug logging has been automatically turned off after ${minutes} minutes")
}

// Utility methods for clean logging
def logDebug(message) {
    if (settings.debug_logging) {
        log.debug "🐛 ${message}"
    }
}

def logInfo(message) {
    log.info "ℹ️ ${message}"
}

def logWarn(message) {
    log.warn "⚠️ ${message}"
}

def logError(message, device = null) {
    log.error "❌ ${message}"
    sendErrorEvent("System Error", message, device)
}

def sendErrorEvent(title, message, device = null) {
    // Send error as event for automation triggers
    def eventData = [
        name: "error",
        value: title,
        descriptionText: message,
        data: [
            timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
            device: device?.label ?: "App",
            severity: "error"
        ]
    ]
    
    if (device) {
        device.sendEvent(eventData)
    } else {
        // Send to app-level event (if supported by platform)
        sendEvent(eventData)
    }
}

// ====================
// AUTHENTICATION
// ====================

def testAuthentication() {
    log.info "🔘 Test Authentication button clicked!"
    log.info "Testing Kia UVO authentication..."
    
    if (!settings.username || !settings.password) {
        log.error "Username and password are required"
        return
    }
    
    authenticate()
}

def authenticate() {
    log.info "Authenticating with Kia UVO..."
    
    // Generate device ID in Python library format: 22 alphanumeric + ":" + 140 URL-safe chars
    def deviceId = generateDeviceId()
    
    // Calculate timezone offset
    def offsetSeconds = TimeZone.getDefault().getOffset(new Date().getTime()) / 1000
    def offsetHours = (int)(offsetSeconds / 60 / 60)
    def currentDate = new Date().format("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"))
    
    // Headers based on successful mitmproxy capture
    def headers = [
        "content-type": "application/json;charset=UTF-8",
        "accept": "application/json, text/plain, */*",
        "accept-encoding": "gzip, deflate, br",
        "accept-language": "en-US,en;q=0.9",
        "apptype": "L",
        "appversion": APP_VERSION,
        "clientid": CLIENT_ID,
        "from": "SPA",
        "host": API_HOST,
        "language": "0",
        "offset": offsetHours.toString(),
        "ostype": "Android",
        "osversion": "11",
        "secretkey": CLIENT_SECRET,
        "to": "APIGW",
        "tokentype": "G",
        "user-agent": USER_AGENT,
        "date": currentDate,
        "deviceid": deviceId
    ]
    
    // JSON body in exact Python format
    def jsonBody = '{"deviceKey": "", "deviceType": 2, "userCredential": {"userId": "' + username + '", "password": "' + password + '"}}'
    
    def params = [
        uri: API_BASE_URL,
        path: AUTH_ENDPOINT,
        headers: headers,
        body: jsonBody
    ]
    
    logDebug "Authentication request: ${params}"
    
    logDebug "Authenticating with Kia UVO servers..."
    
    try {
        httpPost(params) { response ->
            handleAuthResponse(response)
        }
    } catch (Exception e) {
        log.error "Authentication request failed: ${e.message}"
        state.authenticated = false
    }
}

def handleAuthResponse(response) {
    def reCode = response.getStatus()
    def reJson = response.getData()
    
    logDebug "Authentication response: HTTP ${reCode}"
    
    logDebug "Auth response data: ${reJson}"
    
    if (reCode == 200 && reJson.status?.statusCode == 0) {
        // Extract session ID from response headers
        def sessionId = response.getHeaders()?.sid
        if (sessionId) {
            state.sessionId = sessionId
            state.authenticated = true
            log.info "✅ Kia UVO authentication successful!"
            
            // Schedule token refresh if staying logged in
            if (stay_logged_in) {
                runIn(3600, refreshSession) // Refresh every hour
            }
        } else {
            log.error "No session ID found in authentication response headers"
            state.authenticated = false
        }
    } else {
        log.error "Authentication failed: HTTP ${reCode}, Error: ${reJson}"
        if (reJson.status?.errorCode) {
            log.error "Authentication error code: ${reJson.status.errorCode} - ${reJson.status.errorMessage}"
        }
        state.authenticated = false
    }
}

def refreshSession() {
    if (stay_logged_in && state.sessionId) {
        log.info "Refreshing Kia UVO session..."
        authenticate()
    }
}

// ====================
// VEHICLE DISCOVERY
// ====================

def discoverVehicles(isRetry = false) {
    log.info "Discovering Kia vehicles..." + (isRetry ? " (retry)" : "")
    
    if (!state.authenticated || !state.sessionId) {
        log.warn "Not authenticated, authenticating first..."
        authenticate()
        if (!state.authenticated) {
            log.error "Authentication failed, cannot discover vehicles"
            return
        }
    }
    
    // Generate fresh device ID for this request
    def deviceId = generateDeviceId()
    def offsetSeconds = TimeZone.getDefault().getOffset(new Date().getTime()) / 1000
    def offsetHours = (int)(offsetSeconds / 60 / 60)
    def currentDate = new Date().format("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"))
    
    // Headers with session ID
    def headers = [
        "content-type": "application/json;charset=UTF-8",
        "accept": "application/json, text/plain, */*",
        "accept-encoding": "gzip, deflate, br",
        "accept-language": "en-US,en;q=0.9",
        "apptype": "L",
        "appversion": APP_VERSION,
        "clientid": CLIENT_ID,
        "from": "SPA",
        "host": API_HOST,
        "language": "0",
        "offset": offsetHours.toString(),
        "ostype": "Android",
        "osversion": "11",
        "secretkey": CLIENT_SECRET,
        "to": "APIGW",
        "tokentype": "G",
        "user-agent": USER_AGENT,
        "date": currentDate,
        "deviceid": deviceId,
        "sid": state.sessionId
    ]
    
    def params = [
        uri: API_BASE_URL,
        path: VEHICLE_LIST_ENDPOINT,
        headers: headers
    ]
    
    logDebug "Vehicle discovery request: ${params}"
    
    logDebug "Requesting vehicle list from Kia servers..."
    
    try {
        httpGet(params) { response ->
            handleVehicleResponse(response, isRetry)
        }
    } catch (Exception e) {
        log.error "Vehicle discovery request failed: ${e.message}"
        
        // Check if it's a session expiry error (error code 1003) and not already a retry
        if (!isRetry && (e.message?.contains("1003") || e.message?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired during vehicle discovery, clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying vehicle discovery..."
                // Retry with fresh session (mark as retry to prevent infinite loop)
                discoverVehicles(true)
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        } else {
            log.error "Vehicle discovery failed with non-session error: ${e.message}"
        }
    }
}

def handleVehicleResponse(response, isRetry = false) {
    def reCode = response.getStatus()
    def reJson = response.getData()
    
    logDebug "Vehicle discovery response: HTTP ${reCode}"
    
    logDebug "Vehicle response data: ${reJson}"
    
    if (reCode == 200 && reJson.status?.statusCode == 0) {
        logDebug "Successfully received vehicle list from Kia servers"
        def vehicles = reJson.payload?.vehicleSummary
        if (vehicles) {
            log.info "Found ${vehicles.size()} vehicle(s)"
            state.vehicles = vehicles
            
            vehicles.each { vehicle ->
                log.info "Vehicle: ${vehicle.nickName} (${vehicle.modelName} ${vehicle.modelYear}) - VIN: ${vehicle.vin}"
                
                // Update existing devices with fresh vehicleKey
                def existingDevice = getChildDevice("Kia_${vehicle.vin}")
                if (existingDevice) {
                    logDebug "🔄 Updating existing device ${existingDevice.label} with fresh vehicleKey"
                    existingDevice.sendEvent(name: "vehicleKey", value: vehicle.vehicleKey)
                    existingDevice.sendEvent(name: "vinKey", value: vehicle.vehicleKey)
                    existingDevice.updateDataValue("vehicleKey", vehicle.vehicleKey)
                    existingDevice.updateDataValue("vinKey", vehicle.vehicleKey)
                    logDebug "✅ Updated ${existingDevice.label} with fresh keys"
                }
            }
        } else {
            log.warn "No vehicles found in Kia API response"
            state.vehicles = null
        }
    } else {
        log.error "Vehicle discovery failed: HTTP ${reCode}, Error: ${reJson}"
        state.vehicles = null
        
        // Check if it's a session expiry error in the response and not already a retry
        if (!isRetry && (reJson.status?.errorCode == 1003 || reJson.status?.errorMessage?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired (error code 1003 in response), clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying vehicle discovery..."
                // Retry with fresh session (mark as retry to prevent infinite loop)
                discoverVehicles(true)
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        } else {
            log.error "Vehicle discovery failed with error code: ${reJson.status?.errorCode} - ${reJson.status?.errorMessage}"
        }
    }
}



// ====================
// DEVICE CREATION
// ====================

def createVehicleDevices() {
    if (!state.vehicles) {
        log.error "No vehicles found. Please discover vehicles first."
        return
    }
    
    log.info "Creating/updating vehicle devices..."
    
    state.vehicles.each { vehicle ->
        def deviceId = "Kia_${vehicle.vin}"
        def deviceName = vehicle.nickName ?: "Kia ${vehicle.modelName}"
        
        // Check if device already exists
        def existingDevice = getChildDevice(deviceId)
        if (existingDevice) {
            logDebug "🔄 Device already exists for ${vehicle.nickName}, updating with fresh data..."
            
            // Update existing device with fresh vehicle data
            existingDevice.sendEvent(name: "NickName", value: vehicle.nickName)
            existingDevice.sendEvent(name: "Model", value: vehicle.modelName)
            existingDevice.sendEvent(name: "ModelYear", value: vehicle.modelYear)
            existingDevice.sendEvent(name: "Trim", value: vehicle.trim)
            existingDevice.sendEvent(name: "Color", value: vehicle.colorName)
            existingDevice.sendEvent(name: "Odometer", value: vehicle.mileage)
            existingDevice.sendEvent(name: "vehicleKey", value: vehicle.vehicleKey)
            existingDevice.sendEvent(name: "vinKey", value: vehicle.vehicleKey)
            existingDevice.sendEvent(name: "isEV", value: (vehicle.fuelType == 4 ? "true" : "false"))
            
            // Update device data values
            existingDevice.updateDataValue("vehicleKey", vehicle.vehicleKey)
            existingDevice.updateDataValue("vinKey", vehicle.vehicleKey)
            existingDevice.updateDataValue("modelYear", vehicle.modelYear.toString())
            
            log.info "✅ Updated existing device for ${deviceName} (VIN: ${vehicle.vin})"
        } else {
            // Create new device only if it doesn't exist
            try {
                log.info "🆕 Creating new device for ${vehicle.nickName}..."
                def newDevice = addChildDevice(
                    "kia-uvo",
                    "Kia UVO Vehicle Driver",
                    deviceId,
                    [
                        name: "Kia UVO Vehicle Driver",
                        label: deviceName
                    ],
                    [
                        "vin": vehicle.vin,
                        "vehicleKey": vehicle.vehicleKey,
                        "vinKey": vehicle.vehicleKey,  // Same value as vehicleKey
                        "modelYear": vehicle.modelYear
                    ]
                )
                
                if (newDevice) {
                    // Set vehicle attributes for new device
                    newDevice.sendEvent(name: "NickName", value: vehicle.nickName)
                    newDevice.sendEvent(name: "VIN", value: vehicle.vin)
                    newDevice.sendEvent(name: "Model", value: vehicle.modelName)
                    newDevice.sendEvent(name: "ModelYear", value: vehicle.modelYear)
                    newDevice.sendEvent(name: "Trim", value: vehicle.trim)
                    newDevice.sendEvent(name: "Color", value: vehicle.colorName)
                    newDevice.sendEvent(name: "Odometer", value: vehicle.mileage)
                    newDevice.sendEvent(name: "vehicleBrand", value: "kia")
                    newDevice.sendEvent(name: "vehicleRegion", value: "US")
                    newDevice.sendEvent(name: "vehicleKey", value: vehicle.vehicleKey)
                    newDevice.sendEvent(name: "vinKey", value: vehicle.vehicleKey)  // Same value
                    newDevice.sendEvent(name: "isEV", value: (vehicle.fuelType == 4 ? "true" : "false"))
                    
                    log.info "✅ Created new device for ${deviceName} (VIN: ${vehicle.vin})"
                }
            } catch (Exception e) {
                log.error "Failed to create device for ${vehicle.nickName}: ${e.message}"
            }
        }
    }
}

// ====================
// VEHICLE STATUS & COMMANDS
// ====================

def getVehicleStatus(device, isRetry = false) {
    logInfo "🔄 getVehicleStatus called for ${device.label} (cached data)" + (isRetry ? " (retry)" : "")
    logDebug "Current auth state: authenticated=${state.authenticated}, sessionId=${state.sessionId ? 'present' : 'missing'}"
    
    if (!state.authenticated || !state.sessionId) {
        log.warn "Not authenticated, authenticating first..."
        authenticate()
        if (!state.authenticated) {
            log.error "Authentication failed, cannot get vehicle status for ${device.label}"
            return
        }
        log.info "Re-authentication successful, continuing with vehicle status request..."
    }
    
    logInfo "Getting status for vehicle ${device.label}" + (isRetry ? " (retry)" : "")
    
    // Try to get VIN from current value, fallback to device properties
    def vin = device.currentValue("VIN") ?: device.getDataValue("vin")
    def vehicleKey = device.currentValue("vehicleKey") ?: device.getDataValue("vehicleKey")
    def vinKey = device.currentValue("vinKey") ?: device.getDataValue("vinKey") ?: vehicleKey
    
    logDebug "Device data - VIN: ${vin ? 'present' : 'MISSING'}, vehicleKey: ${vehicleKey ? 'present' : 'missing'}, vinKey: ${vinKey ? 'present' : 'missing'}"
    
    if (!vin) {
        log.error "❌ No VIN found for device ${device.label} - device may need to be recreated"
        return
    }
    
    if (!vinKey) {
        log.error "❌ No vinKey/vehicleKey found for device ${device.label} - device may need to be recreated after fresh vehicle discovery"
        return
    }
    
    logDebug "Getting status for VIN: ${vin}, vinKey: ${vinKey}"
    
    // Generate fresh device ID and headers for this request
    def deviceId = generateDeviceId()
    def offsetSeconds = TimeZone.getDefault().getOffset(new Date().getTime()) / 1000
    def offsetHours = (int)(offsetSeconds / 60 / 60)
    def currentDate = new Date().format("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"))
    
    // Headers with session ID and vehicle key
    def headers = [
        "content-type": "application/json;charset=UTF-8",
        "accept": "application/json, text/plain, */*",
        "accept-encoding": "gzip, deflate, br",
        "accept-language": "en-US,en;q=0.9",
        "apptype": "L",
        "appversion": APP_VERSION,
        "clientid": CLIENT_ID,
        "from": "SPA",
        "host": API_HOST,
        "language": "0",
        "offset": offsetHours.toString(),
        "ostype": "Android",
        "osversion": "11",
        "secretkey": CLIENT_SECRET,
        "to": "APIGW",
        "tokentype": "G",
        "user-agent": USER_AGENT,
        "date": currentDate,
        "deviceid": deviceId,
        "sid": state.sessionId,
        "vinkey": vinKey
    ]
    
    // JSON body - try requesting more specific EV data
    def jsonBody = '{"vehicleConfigReq": {"airTempRange": "1", "maintenance": "1", "seatHeatCoolOption": "1", "vehicle": "1", "vehicleFeature": "1"}, "vehicleInfoReq": {"drivingActivty": "1", "dtc": "1", "enrollment": "1", "functionalCards": "1", "location": "1", "vehicleStatus": "1", "weather": "1"}, "vinKey": ["' + vinKey + '"]}'
    
    logDebug "🔧 Trying enhanced request to get fresh EV data..."
    
    def params = [
        uri: API_BASE_URL,
        path: VEHICLE_INFO_ENDPOINT,
        headers: headers,
        body: jsonBody
    ]
    
    logDebug "Vehicle status request: ${params}"
    
    logDebug "Requesting vehicle status for ${device.label}..."
    
    try {
        httpPost(params) { response ->
            handleVehicleStatusResponse(response, device, isRetry)
        }
    } catch (Exception e) {
        log.error "Vehicle status request failed for ${device.label}: ${e.message}"
        
        // Check if it's a session expiry error (error code 1003) and not already a retry
        if (!isRetry && (e.message?.contains("1003") || e.message?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired detected, clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying vehicle status request..."
                // Retry with fresh session (mark as retry to prevent infinite loop)
                getVehicleStatus(device, true)
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        }
        // Check if it's a session error (error code 1005)
        else if (e.message?.contains("1005") || e.message?.contains("Invalid vehicle")) {
            log.error "Invalid vehicle error (1005) - vehicle key is stale for ${device.label}"
            log.warn "Automatically refreshing vehicle keys..."
            
            // Automatically trigger vehicle discovery to refresh stale keys
            discoverVehicles()
            
            // After discovery, retry the status request once
            if (!isRetry) {
                log.info "Vehicle keys refreshed, retrying status request for ${device.label}..."
                runIn(2, "retryVehicleStatus", [data: [deviceId: device.getId()]])
            }
        } else {
            log.error "Vehicle status request failed for ${device.label} with error: ${e.message}"
        }
    }
}

def handleVehicleStatusResponse(response, device, isRetry = false) {
    def reCode = response.getStatus()
    def reJson = response.getData()
    
    logDebug "Vehicle status response for ${device.label}: HTTP ${reCode}"
    
    logDebug "Vehicle status response data: ${reJson}"
    
    if (reCode == 200 && reJson.status?.statusCode == 0) {
        logDebug "Successfully received vehicle status data for ${device.label}"
        def vehicleInfo = reJson.payload?.vehicleInfoList?.get(0)?.lastVehicleInfo
        def vehicleConfig = reJson.payload?.vehicleInfoList?.get(0)?.vehicleConfig?.vehicleDetail
        
        if (vehicleInfo && vehicleConfig) {
            logDebug "Parsing vehicle status data for ${device.label}"
            
                        // Prepare status data map
            def statusData = [:]
            
            // Basic vehicle info (LastRefreshTime only set on successful API response)
            statusData["LastRefreshTime"] = new Date().format("yyyy-MM-dd HH:mm:ss")
            statusData["Odometer"] = vehicleConfig.vehicle?.mileage
            statusData["NickName"] = vehicleInfo.vehicleNickName
            
            // Vehicle status from vehicleStatusRpt
            def vehicleStatus = vehicleInfo.vehicleStatusRpt?.vehicleStatus
            if (vehicleStatus) {
                // Engine and basic status
                statusData["Engine"] = vehicleStatus.engine ? "Running" : "Off"
                statusData["DoorLocks"] = vehicleStatus.doorLock ? "Locked" : "Unlocked"
                
                // Door status details
                def doorStatus = vehicleStatus.doorStatus
                if (doorStatus) {
                    statusData["FrontLeftDoor"] = doorStatus.frontLeft == 0 ? "Closed" : "Open"
                    statusData["FrontRightDoor"] = doorStatus.frontRight == 0 ? "Closed" : "Open"
                    statusData["BackLeftDoor"] = doorStatus.backLeft == 0 ? "Closed" : "Open"
                    statusData["BackRightDoor"] = doorStatus.backRight == 0 ? "Closed" : "Open"
                    statusData["Trunk"] = doorStatus.trunk == 0 ? "Closed" : "Open"
                    statusData["Hood"] = doorStatus.hood == 0 ? "Closed" : "Open"
                }
                
                // Climate control - separate temperature value from unit
                def climate = vehicleStatus.climate
                if (climate) {
                    if (climate.airTemp?.value != null) {
                        statusData["AirTemp"] = climate.airTemp.value.toString()
                        statusData["AirTempUnit"] = (climate.airTemp?.unit == 1 ? "F" : "C")
                    }
                    statusData["AirControl"] = climate.airCtrl ? "On" : "Off"
                    statusData["Defrost"] = climate.defrost ? "On" : "Off"
                }
                
                // EV specific status
                def evStatus = vehicleStatus.evStatus
                if (evStatus) {
                    logDebug "🔋 Found evStatus, checking battery and range data..."
                    statusData["isEV"] = "true"
                    
                    // Debug battery status extraction
                    logDebug "🔋 batteryStatus field: ${evStatus.batteryStatus}"
                    statusData["BatterySoC"] = evStatus.batteryStatus?.toString()
                    
                    statusData["ChargingStatus"] = evStatus.batteryCharge ? "Charging" : "Not Charging"
                    // batteryPlugin: 4=charging, 2=connected, 1=unplugged
                    logDebug "🔌 CACHED: batteryPlugin value: ${evStatus.batteryPlugin}"
                    statusData["PlugStatus"] = (evStatus.batteryPlugin >= 2) ? "Plugged In" : "Unplugged"
                    
                    // Charging power (realTimePower is in kW) - separate value from unit
                    if (evStatus.realTimePower != null) {
                        logDebug "⚡ Found charging power: ${evStatus.realTimePower} kW"
                        statusData["ChargingPower"] = evStatus.realTimePower.toString()
                        statusData["ChargingPowerUnit"] = "kW"
                    } else {
                        log.debug "⚡ No charging power data available"
                        // Don't send ChargingPower event if not available
                    }
                    
                    // EV Range
                    logDebug "🏁 drvDistance array: ${evStatus.drvDistance}"
                    def drvDistance = evStatus.drvDistance?.get(0)?.rangeByFuel
                    logDebug "🏁 extracted drvDistance: ${drvDistance}"
                    if (drvDistance) {
                        logDebug "🏁 evModeRange: ${drvDistance.evModeRange?.value}"
                        statusData["EVRange"] = drvDistance.evModeRange?.value?.toString()
                        statusData["TotalRange"] = drvDistance.totalAvailableRange?.value?.toString()
                    } else {
                        log.warn "🏁 No drvDistance data found in evStatus"
                    }
                    
                    // Charging time remaining - calculate all formats
                    def remainChargeTime = evStatus.remainChargeTime?.get(0)
                    if (remainChargeTime?.timeInterval?.value) {
                        def totalMinutes = remainChargeTime.timeInterval.value as Integer
                        
                        // Calculate HH:mm format
                        def hours = totalMinutes.intdiv(60)
                        def minutes = totalMinutes % 60
                        def hhMmFormat = String.format("%d:%02d", hours, minutes)
                        
                        // Calculate total hours with decimals
                        def totalHours = totalMinutes / 60.0
                        
                        // Calculate estimated completion time
                        def now = new Date()
                        def completionTime = new Date(now.time + (totalMinutes * 60 * 1000))
                        def completionTimeStr = completionTime.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                        
                        statusData["ChargeTimeRemaining"] = hhMmFormat
                        statusData["ChargeTimeRemainingMinutes"] = totalMinutes.toString()
                        statusData["ChargeTimeRemainingHours"] = String.format("%.1f", totalHours)
                        statusData["EstimatedChargeCompletionTime"] = completionTimeStr
                    }
                    

                    
                    // Debug: Show all evStatus fields if debug logging enabled
                    logDebug "🔍 All evStatus fields:"
                    evStatus.each { key, value ->
                        logDebug "  ${key}: ${value}"
                    }
                } else {
                    log.warn "❌ No evStatus found in vehicleStatus - vehicle may not be an EV or data missing"
                    logDebug "🔍 Available vehicleStatus fields: ${vehicleStatus.keySet()}"
                    statusData["isEV"] = "false"
                    // Could add fuel level here for non-EV vehicles
                }
                

                
                // 12V auxiliary battery status
                def batteryStatus = vehicleStatus.batteryStatus
                if (batteryStatus) {
                    statusData["AuxBattery"] = batteryStatus.stateOfCharge?.toString() + "%"
                }
                
                // Tire pressure
                def tirePressure = vehicleStatus.tirePressure
                if (tirePressure) {
                    statusData["TirePressureWarning"] = tirePressure.all == 0 ? "Normal" : "Warning"
                }
            }
            
            // Location data
            def location = vehicleInfo.location
            if (location && location.coord) {
                statusData["Latitude"] = location.coord.lat?.toString()
                statusData["Longitude"] = location.coord.lon?.toString()
                statusData["Location"] = "${location.coord.lat}, ${location.coord.lon}"
                statusData["GoogleMapsURL"] = "https://maps.google.com/maps?q=${location.coord.lat},${location.coord.lon}"
                statusData["Altitude"] = location.coord.alt?.toString()
                statusData["Heading"] = location.head?.toString()
                statusData["Speed"] = location.speed?.value?.toString()
                
                // Calculate home status for statusData
                def homeLat = device.getSetting("homeLatitude")
                def homeLon = device.getSetting("homeLongitude")
                def homeRadius = device.getSetting("homeRadius") ?: 100
                if (homeLat && homeLon) {
                    def distance = calculateDistance(location.coord.lat as Double, location.coord.lon as Double, homeLat as Double, homeLon as Double)
                    if (distance != null) {
                        statusData["isHome"] = (distance <= homeRadius) ? "true" : "false"
                    } else {
                        statusData["isHome"] = "Unknown"
                    }
                } else {
                    statusData["isHome"] = "Unknown"
                }
                
                // Update location on device
                if (location.coord.lat && location.coord.lon) {
                    try {
                        device.updateLocation(location.coord.lat.toString(), location.coord.lon.toString())
                        
                        // Update additional location attributes
                        device.sendEvent(name: "GoogleMapsURL", value: "https://maps.google.com/maps?q=${location.coord.lat},${location.coord.lon}")
                        if (location.speed?.value) {
                            def unit = location.speed.unit == 1 ? "mph" : "km/h"
                            device.sendEvent(name: "Speed", value: location.speed.value, unit: unit)
                        }
                        if (location.head) {
                            device.sendEvent(name: "Heading", value: location.head.toString())
                        }
                        if (location.coord.alt) {
                            device.sendEvent(name: "Altitude", value: location.coord.alt, unit: "m")
                        }
                        
                        // Update home status
                        updateHomeStatus(device, location.coord.lat, location.coord.lon)
                    } catch (Exception e) {
                        log.error "❌ Failed to call updateLocation: ${e.message}"
                    }
                }
            }
            
            // Update all status data on device
            try {
                device.updateVehicleStatus(statusData)
                logInfo "✅ Successfully updated vehicle status"
            } catch (Exception e) {
                log.error "❌ Failed to call updateVehicleStatus: ${e.message}"
                
                // Fallback - manually update attributes
                logDebug "🔄 Falling back to manual attribute updates..."
                statusData.each { key, value ->
                    if (value != null) {
                        try {
                            device.sendEvent(name: key, value: value.toString())
                            if(key == "BatterySoC")
                            {
                                device.sendEvent(name: 'battery', value: value.toString())
                            }
                        } catch (Exception e2) {
                            log.warn "Failed to set attribute ${key}: ${e2.message}"
                        }
                    }
                }
            }
            
            logInfo "✅ Updated vehicle status (cached): ${device.label} - Battery ${statusData.BatterySoC}%, Range ${statusData.EVRange} miles, 12V Aux ${statusData.AuxBattery}, ${statusData.DoorLocks}"
            
        } else {
            log.error "No vehicle info found in response for ${device.label}"
        }
    } else {
        log.error "Vehicle status request failed for ${device.label}: HTTP ${reCode}, Error: ${reJson}"
        
        // Check if it's a session expiry error in the response and not already a retry
        if (!isRetry && (reJson.status?.errorCode == 1003 || reJson.status?.errorMessage?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired (error code 1003 in response), clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying vehicle status request..."
                // Retry with fresh session (mark as retry to prevent infinite loop)
                getVehicleStatus(device, true)
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        } else if (reJson.status?.errorCode == 1005) {
            log.error "Invalid vehicle error (code 1005) - vehicle key is stale for ${device.label}"
            log.warn "Automatically refreshing vehicle keys..."
            
            // Automatically trigger vehicle discovery to refresh stale keys
            discoverVehicles()
            
            // After discovery, retry the status request once
            if (!isRetry) {
                log.info "Vehicle keys refreshed, retrying status request for ${device.label}..."
                runIn(2, "retryVehicleStatus", [data: [deviceNetworkId: device.deviceNetworkId]])
            }
        } else {
            log.error "Vehicle status failed for ${device.label} with error code: ${reJson.status?.errorCode} - ${reJson.status?.errorMessage}"
        }
    }
}

def retryVehicleStatus(data) {
    logDebug "Retrying vehicle status after key refresh..."
    def device = getChildDevice(data.deviceNetworkId)
    if (device) {
        getVehicleStatus(device, true) // Mark as retry to prevent infinite loops
    } else {
        log.error "Could not find device with network ID: ${data.deviceNetworkId}"
    }
}

def refreshVehicleStatus(device, isRetry = false) {
    logInfo "🔄 refreshVehicleStatus called for ${device.label} (polling vehicle)" + (isRetry ? " (retry)" : "")
    logDebug "Current auth state: authenticated=${state.authenticated}, sessionId=${state.sessionId ? 'present' : 'missing'}"
    
    if (!state.authenticated || !state.sessionId) {
        log.warn "Not authenticated, authenticating first..."
        authenticate()
        if (!state.authenticated) {
            log.error "Authentication failed, cannot refresh vehicle status for ${device.label}"
            return
        }
        log.info "Re-authentication successful, continuing with vehicle status refresh..."
    }
    
    logDebug "Polling vehicle for fresh status: ${device.label}" + (isRetry ? " (retry)" : "")
    
    // Try to get VIN from current value, fallback to device properties
    def vin = device.currentValue("VIN") ?: device.getDataValue("vin")
    def vehicleKey = device.currentValue("vehicleKey") ?: device.getDataValue("vehicleKey")
    def vinKey = device.currentValue("vinKey") ?: device.getDataValue("vinKey") ?: vehicleKey
    
    logDebug "Device data - VIN: ${vin ? 'present' : 'MISSING'}, vehicleKey: ${vehicleKey ? 'present' : 'missing'}, vinKey: ${vinKey ? 'present' : 'missing'}"
    
    if (!vin) {
        logError "No VIN found for device ${device.label} - device may need to be recreated", device
        return
    }
    
    if (!vinKey) {
        log.error "❌ No vinKey/vehicleKey found for device ${device.label} - device may need to be recreated after fresh vehicle discovery"
        return
    }
    
    logDebug "Refreshing fresh status for VIN: ${vin}, vinKey: ${vinKey}"
    
    // Generate fresh device ID and headers for this request
    def deviceId = generateDeviceId()
    def offsetSeconds = TimeZone.getDefault().getOffset(new Date().getTime()) / 1000
    def offsetHours = (int)(offsetSeconds / 60 / 60)
    def currentDate = new Date().format("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"))
    
    // Headers with session ID and vehicle key for fresh status
    def headers = [
        "content-type": "application/json;charset=UTF-8",
        "accept": "application/json, text/plain, */*",
        "accept-encoding": "gzip, deflate, br",
        "accept-language": "en-US,en;q=0.9",
        "apptype": "L",
        "appversion": APP_VERSION,
        "clientid": CLIENT_ID,
        "from": "SPA",
        "host": API_HOST,
        "language": "0",
        "offset": offsetHours.toString(),
        "ostype": "Android",
        "osversion": "11",
        "secretkey": CLIENT_SECRET,
        "to": "APIGW",
        "tokentype": "G",
        "user-agent": USER_AGENT,
        "date": currentDate,
        "deviceid": deviceId,
        "sid": state.sessionId,
        "vinkey": vinKey
    ]
    
    // Correct RVS request format discovered via mitmproxy capture from working Python library
    def jsonBody = '{"requestType": 0}'
    
    logInfo "🔧 Polling vehicle directly for fresh data (may take 10-30 seconds)..."
    logDebug "🔧 Request body: ${jsonBody}"
    
    def params = [
        uri: API_BASE_URL,
        path: VEHICLE_STATUS_FRESH_ENDPOINT,
        headers: headers,
        body: jsonBody,
        timeout: 35  // Increase timeout to 35 seconds for RVS calls (vehicle polling can take 10-30 seconds)
    ]
    
    logDebug "Fresh vehicle status request: ${params}"
    
    logDebug "Polling vehicle for status: ${device.label}..."
    
    try {
        httpPost(params) { response ->
            handleFreshVehicleStatusResponse(response, device, isRetry)
        }
    } catch (Exception e) {
        log.error "Fresh vehicle status request failed for ${device.label}: ${e.message}"
        
        // Check if it's a session expiry error (error code 1003) and not already a retry
        if (!isRetry && (e.message?.contains("1003") || e.message?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired detected, clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying fresh vehicle status request..."
                // Retry with fresh session (mark as retry to prevent infinite loops)
                refreshVehicleStatus(device, true)
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        }
        // Check if it's a session error (error code 1005)
        else if (!isRetry && (e.message?.contains("1005") || e.message?.contains("Invalid vehicle"))) {
            log.error "Invalid vehicle error (1005) - vehicle key is stale for ${device.label}"
            log.warn "Clearing session and re-authenticating to refresh vehicle keys..."
            
            // Clear the session and re-authenticate to get fresh vehicle keys
            state.authenticated = false
            state.sessionId = null
            
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying fresh status request for ${device.label}..."
                refreshVehicleStatus(device, true) // Retry once with fresh session
            } else {
                log.error "Re-authentication failed after vehicle key error"
            }
        } else if (!isRetry && e.message?.contains("9001")) {
            log.error "Payload format error (9001) - this should not happen with the correct requestType format"
            log.error "Request body was: ${jsonBody}"
        } else {
            log.error "Fresh vehicle status request failed for ${device.label} with error: ${e.message}"
        }
    }
}

def handleFreshVehicleStatusResponse(response, device, isRetry = false) {
    def reCode = response.getStatus()
    def reJson = response.getData()
    
    logDebug "Fresh vehicle status response for ${device.label}: HTTP ${reCode}"
    
    logDebug "Fresh vehicle status response data: ${reJson}"
    
    if (reCode == 200 && reJson.status?.statusCode == 0) {
        logDebug "✅ Successfully received fresh vehicle data for ${device.label}"
        def vehicleStatusRpt = reJson.payload?.vehicleStatusRpt
        
        if (vehicleStatusRpt) {
            logDebug "✅ Parsing FRESH vehicle status data for ${device.label}"
            
            // Check API data timestamps
            if (vehicleStatusRpt?.reportDate?.utc) {
                logDebug "📅 Fresh API data timestamp: ${vehicleStatusRpt.reportDate.utc} (offset: ${vehicleStatusRpt.reportDate.offset})"
            }
            
            // Prepare status data map
            def statusData = [:]
            
            // Basic vehicle info (LastRefreshTime only set on successful API response)
            statusData["LastRefreshTime"] = new Date().format("yyyy-MM-dd HH:mm:ss")
            
            // Vehicle status from fresh response
            def vehicleStatus = vehicleStatusRpt.vehicleStatus
            if (vehicleStatus) {
                // Engine and basic status
                statusData["Engine"] = vehicleStatus.engine ? "Running" : "Off"
                statusData["DoorLocks"] = vehicleStatus.doorLock ? "Locked" : "Unlocked"
                
                // Door status details
                def doorStatus = vehicleStatus.doorStatus
                if (doorStatus) {
                    statusData["FrontLeftDoor"] = doorStatus.frontLeft == 0 ? "Closed" : "Open"
                    statusData["FrontRightDoor"] = doorStatus.frontRight == 0 ? "Closed" : "Open"
                    statusData["BackLeftDoor"] = doorStatus.backLeft == 0 ? "Closed" : "Open"
                    statusData["BackRightDoor"] = doorStatus.backRight == 0 ? "Closed" : "Open"
                    statusData["Trunk"] = doorStatus.trunk == 0 ? "Closed" : "Open"
                    statusData["Hood"] = doorStatus.hood == 0 ? "Closed" : "Open"
                }
                
                // Climate control - separate temperature value from unit
                def climate = vehicleStatus.climate
                if (climate) {
                    if (climate.airTemp?.value != null) {
                        statusData["AirTemp"] = climate.airTemp.value.toString()
                        statusData["AirTempUnit"] = (climate.airTemp?.unit == 1 ? "F" : "C")
                    }
                    statusData["AirControl"] = climate.airCtrl ? "On" : "Off"
                    statusData["Defrost"] = climate.defrost ? "On" : "Off"
                }
                
                // EV specific status from fresh response
                def evStatus = vehicleStatus.evStatus
                if (evStatus) {
                    logDebug "🔋 FRESH: Found evStatus data with battery ${evStatus.batteryStatus}%"
                    statusData["isEV"] = "true"
                    statusData["BatterySoC"] = evStatus.batteryStatus?.toString()
                    statusData["ChargingStatus"] = evStatus.batteryCharge ? "Charging" : "Not Charging"
                    // batteryPlugin: 4=charging, 2=connected, 1=unplugged
                    logDebug "🔌 FRESH: batteryPlugin value: ${evStatus.batteryPlugin}"
                    statusData["PlugStatus"] = (evStatus.batteryPlugin >= 2) ? "Plugged In" : "Unplugged"
                    
                    // Charging power from fresh data (realTimePower is in kW)
                    if (evStatus.realTimePower != null) {
                        logDebug "⚡ FRESH: Found charging power: ${evStatus.realTimePower} kW"
                        statusData["ChargingPower"] = evStatus.realTimePower.toString()
                        statusData["ChargingPowerUnit"] = "kW"
                    } else {
                        log.debug "⚡ FRESH: No charging power data available"
                        // Don't send ChargingPower event if not available
                    }
                    
                    // EV Range from fresh data
                    def drvDistance = evStatus.drvDistance?.get(0)?.rangeByFuel
                    if (drvDistance) {
                        logDebug "🏁 FRESH: Found range data - EV: ${drvDistance.evModeRange?.value}, Total: ${drvDistance.totalAvailableRange?.value}"
                        statusData["EVRange"] = drvDistance.evModeRange?.value?.toString()
                        statusData["TotalRange"] = drvDistance.totalAvailableRange?.value?.toString()
                    } else {
                        log.warn "🏁 FRESH: No range data found in evStatus"
                    }
                    
                    // Charging time remaining - calculate all formats
                    def remainChargeTime = evStatus.remainChargeTime?.get(0)
                    if (remainChargeTime?.timeInterval?.value) {
                        def totalMinutes = remainChargeTime.timeInterval.value as Integer
                        
                        // Calculate HH:mm format
                        def hours = totalMinutes.intdiv(60)
                        def minutes = totalMinutes % 60
                        def hhMmFormat = String.format("%d:%02d", hours, minutes)
                        
                        // Calculate total hours with decimals
                        def totalHours = totalMinutes / 60.0
                        
                        // Calculate estimated completion time
                        def now = new Date()
                        def completionTime = new Date(now.time + (totalMinutes * 60 * 1000))
                        def completionTimeStr = completionTime.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                        
                        statusData["ChargeTimeRemaining"] = hhMmFormat
                        statusData["ChargeTimeRemainingMinutes"] = totalMinutes.toString()
                        statusData["ChargeTimeRemainingHours"] = String.format("%.1f", totalHours)
                        statusData["EstimatedChargeCompletionTime"] = completionTimeStr
                    }
                } else {
                    log.warn "❌ FRESH: No evStatus found in fresh response - vehicle may not be an EV"
                    statusData["isEV"] = "false"
                }
                
                // 12V auxiliary battery status (if present) - separate value from unit
                def batteryStatus = vehicleStatus.batteryStatus
                if (batteryStatus && batteryStatus.stateOfCharge != null) {
                    statusData["AuxBattery"] = batteryStatus.stateOfCharge.toString()
                    statusData["AuxBatteryUnit"] = "%"
                }
                
                // Location data
                def location = vehicleStatusRpt.location
                if (location && location.coord) {
                    statusData["Latitude"] = location.coord.lat?.toString()
                    statusData["Longitude"] = location.coord.lon?.toString()
                    statusData["Location"] = "${location.coord.lat}, ${location.coord.lon}"
                    statusData["GoogleMapsURL"] = "https://maps.google.com/maps?q=${location.coord.lat},${location.coord.lon}"
                    statusData["Altitude"] = location.coord.alt?.toString()
                    statusData["Heading"] = location.head?.toString()
                    statusData["Speed"] = location.speed?.value?.toString()
                    
                    // Calculate home status for statusData
                    def homeLat = device.getSetting("homeLatitude")
                    def homeLon = device.getSetting("homeLongitude")
                    def homeRadius = device.getSetting("homeRadius") ?: 100
                    if (homeLat && homeLon) {
                        def distance = calculateDistance(location.coord.lat as Double, location.coord.lon as Double, homeLat as Double, homeLon as Double)
                        if (distance != null) {
                            statusData["isHome"] = (distance <= homeRadius) ? "true" : "false"
                        } else {
                            statusData["isHome"] = "Unknown"
                        }
                    } else {
                        statusData["isHome"] = "Unknown"
                    }
                }
                
                // Update all status data on device
                try {
                    device.updateVehicleStatus(statusData)
                    logDebug "✅ Successfully updated FRESH vehicle status"
                } catch (Exception e) {
                    log.error "❌ Failed to call updateVehicleStatus: ${e.message}"
                    
                    // Fallback - manually update attributes
                    logDebug "🔄 Falling back to manual attribute updates..."
                    statusData.each { key, value ->
                        if (value != null) {
                            try {
                                device.sendEvent(name: key, value: value.toString())
                            if(key == "BatterySoC")
                            {
                                device.sendEvent(name: 'battery', value: value.toString())
                            }
                            } catch (Exception e2) {
                                log.warn "Failed to set attribute ${key}: ${e2.message}"
                            }
                        }
                    }
                }
                
                // Update location on device
                if (location?.coord?.lat && location?.coord?.lon) {
                    try {
                        device.updateLocation(location.coord.lat.toString(), location.coord.lon.toString())
                        
                        // Update additional location attributes
                        device.sendEvent(name: "GoogleMapsURL", value: "https://maps.google.com/maps?q=${location.coord.lat},${location.coord.lon}")
                        if (location.speed?.value) {
                            def unit = location.speed.unit == 1 ? "mph" : "km/h"
                            device.sendEvent(name: "Speed", value: location.speed.value, unit: unit)
                        }
                        if (location.head) {
                            device.sendEvent(name: "Heading", value: location.head.toString())
                        }
                        if (location.coord.alt) {
                            device.sendEvent(name: "Altitude", value: location.coord.alt, unit: "m")
                        }
                        
                        // Update home status
                        updateHomeStatus(device, location.coord.lat, location.coord.lon)
                        
                        log.info "📍 Updated fresh location: ${location.coord.lat}, ${location.coord.lon}"
                        
                        // Check if this was a specific location request
                        if (state.locationRequestFor == device.getDeviceNetworkId()) {
                            log.info "✅ Location command successful for ${device.label}: ${location.coord.lat}, ${location.coord.lon}"
                            // Clear the flag
                            state.locationRequestFor = null
                        }
                    } catch (Exception e) {
                        log.error "❌ Failed to call updateLocation: ${e.message}"
                        
                        // If this was a location request, report the error
                        if (state.locationRequestFor == device.getDeviceNetworkId()) {
                            log.error "❌ Location command failed for ${device.label}: Failed to update location"
                            state.locationRequestFor = null
                        }
                    }
                } else {
                    log.warn "📍 No location data found in fresh response"
                    
                    // If this was a specific location request, report the failure
                    if (state.locationRequestFor == device.getDeviceNetworkId()) {
                        log.error "❌ Location command failed for ${device.label}: No location data found in vehicle response"
                        state.locationRequestFor = null
                    }
                }
                
                logInfo "✅ Updated vehicle status (fresh): ${device.label} - Battery ${statusData.BatterySoC}%, Range ${statusData.EVRange} miles, 12V Aux ${statusData.AuxBattery}, ${statusData.DoorLocks}"
                
            } else {
                log.error "No vehicle status found in fresh response for ${device.label}"
            }
        } else {
            log.error "No vehicleStatusRpt found in fresh response for ${device.label}"
        }
    } else {
        log.error "Fresh vehicle status request failed for ${device.label}: HTTP ${reCode}, Error: ${reJson}"
        
        // Check if it's a session expiry error in the response and not already a retry
        if (!isRetry && (reJson.status?.errorCode == 1003 || reJson.status?.errorMessage?.contains("Session Key is either invalid or expired"))) {
            log.warn "Session expired (error code 1003 in response), clearing session and re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, refreshing vehicle data and retrying fresh vehicle status request..."
                // Refresh vehicle discovery to get updated vehicle keys for the new session
                discoverVehicles()
                // Give the device data update a moment to complete, then retry
                runIn(2, "retryVehicleStatusRefresh", [data: [deviceNetworkId: device.deviceNetworkId]])
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        } else if (reJson.status?.errorCode == 1005 && !isRetry) {
            log.warn "Invalid vehicle error (code 1005) - vehicle key is stale, refreshing vehicle data and retrying..."
            // Refresh vehicle discovery to get updated vehicle keys
            discoverVehicles()
            // Give the device data update a moment to complete, then retry
            runIn(2, "retryVehicleStatusRefresh", [data: [deviceNetworkId: device.deviceNetworkId]])
        } else if (reJson.status?.errorCode == 9001 && !isRetry) {
            log.error "Payload format error (9001) - this should not happen with the correct requestType format"
            log.error "Request body was: ${jsonBody}"
        } else {
            log.error "Fresh vehicle status failed for ${device.label} with error code: ${reJson.status?.errorCode} - ${reJson.status?.errorMessage}"
        }
    }
}


def sendVehicleCommand(device, command, isRetry = false) {
    log.info "Sending command '${command}' to vehicle ${device.label}${isRetry ? ' (retry)' : ''}"
    
    if (!state.authenticated || !state.sessionId) {
        log.warn "Not authenticated, authenticating first..."
        authenticate()
        if (!state.authenticated) {
            log.error "Authentication failed, cannot send vehicle command"
            return false
        }
    }
    
    // Try to get VIN from current value, fallback to device properties
    def vin = device.currentValue("VIN") ?: device.getDataValue("vin")
    def vehicleKey = device.currentValue("vehicleKey") ?: device.getDataValue("vehicleKey")
    
    if (!vin) {
        log.error "No VIN found for device ${device.label} - waiting for attributes to be set"
        return false
    }
    
    if (!vehicleKey) {
        log.error "No vehicleKey found for device ${device.label} - device may need to be recreated"
        return false
    }
    
    if (isRetry) {
        log.info "Sending command '${command}' to vehicle ${device.label} (retry)"
        log.info "🔑 Using refreshed vehicleKey: ${vehicleKey} for VIN: ${vin}"
    } else {
        log.info "Sending command '${command}' to vehicle ${device.label}"
        logDebug "Sending command '${command}' to VIN: ${vin}, vehicleKey: ${vehicleKey}"
    }
    
    // Generate headers for command request
    def deviceId = generateDeviceId()
    def offsetSeconds = TimeZone.getDefault().getOffset(new Date().getTime()) / 1000
    def offsetHours = (int)(offsetSeconds / 60 / 60)
    def currentDate = new Date().format("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"))
    
    def headers = [
        "content-type": "application/json;charset=UTF-8",
        "accept": "application/json, text/plain, */*",
        "accept-encoding": "gzip, deflate, br",
        "accept-language": "en-US,en;q=0.9",
        "apptype": "L",
        "appversion": APP_VERSION,
        "clientid": CLIENT_ID,
        "from": "SPA",
        "host": API_HOST,
        "language": "0",
        "offset": offsetHours.toString(),
        "ostype": "Android",
        "osversion": "11",
        "secretkey": CLIENT_SECRET,
        "to": "APIGW",
        "tokentype": "G",
        "user-agent": USER_AGENT,
        "date": currentDate,
        "deviceid": deviceId,
        "sid": state.sessionId,
        "vinkey": vehicleKey
    ]
    
    // Determine endpoint, method, and body based on command
    def endpoint = ""
    def method = "GET"  // Most commands are GET requests
    def jsonBody = null
    def successMessage = ""
    def timeout = 15  // Most commands are quick
    
    switch(command) {
        case "lock":
            endpoint = LOCK_ENDPOINT
            method = "GET"
            successMessage = "Vehicle locked successfully"
            break
        case "unlock":
            endpoint = UNLOCK_ENDPOINT
            method = "GET"
            successMessage = "Vehicle unlocked successfully"
            break
        case "start":
            // Climate start command using device preferences
            endpoint = CLIMATE_START_ENDPOINT
            method = "POST"
            timeout = 30  // Climate start commands may take longer
            
            // Get device preferences with defaults
            def temp = device.getSetting("climateTemp") ?: 72
            def duration = device.getSetting("climateDuration") ?: 10
            def defrost = device.getSetting("climateDefrost") ?: false
            def heatedSteering = device.getSetting("climateHeatedSteering") ?: false
            def heatedSeats = device.getSetting("climateHeatedSeats") ?: false
            def cooledSeats = device.getSetting("climateCooledSeats") ?: false
            def useDetailed = device.getSetting("useDetailedClimate") ?: false
            
            // Choose API format based on device preference
            if (useDetailed) {
                // Complex format with all user preferences
                jsonBody = buildClimateRequestBody(temp, duration, defrost, heatedSteering, heatedSeats, cooledSeats)
                logDebug "🌡️ Using detailed climate API format with all accessories"
            } else {
                // Simple format with just basic temperature and duration (no accessories)
                jsonBody = buildClimateRequestBody(temp, duration, defrost, false, false, false)
                logDebug "🌡️ Using basic climate API format (temp/duration only)"
            }
            
            successMessage = "Climate start requested (${temp}°F, ${duration} min)"
            if (defrost) successMessage += " with defrost"
            if (heatedSteering) successMessage += " + heated steering"
            if (heatedSeats) successMessage += " + heated seats"
            if (cooledSeats) successMessage += " + cooled seats"
            successMessage += useDetailed ? " [detailed]" : " [basic]"
            
            logDebug "🌡️ Climate settings: ${temp}°F, ${duration}min, defrost:${defrost}, steering:${heatedSteering}, heated seats:${heatedSeats}, cooled seats:${cooledSeats}"
            break
        case "stop":
            endpoint = CLIMATE_STOP_ENDPOINT
            method = "GET"
            successMessage = "Climate control stopped successfully"
            break
        case "start_charge":
            endpoint = CHARGE_START_ENDPOINT
            method = "POST"
            jsonBody = '{"chargeRatio": 100}'
            successMessage = "Charging started successfully"
            timeout = 20
            break
        case "stop_charge":
            endpoint = CHARGE_STOP_ENDPOINT
            method = "GET"
            successMessage = "Charging stopped successfully"
            break
        case "honk":
        case "flash":
        case "horn_lights":
            endpoint = HORN_LIGHTS_ON_ENDPOINT
            method = "GET"
            successMessage = "Horn and lights activated successfully"
            break
        case "stop_horn_lights":
            endpoint = HORN_LIGHTS_OFF_ENDPOINT
            method = "GET"
            successMessage = "Horn and lights stopped successfully"
            break
        case "location":
            // Try the dedicated location endpoint first, fallback to vehicle status if it fails
            method = "POST"  // Try POST instead of GET
            endpoint = LOCATION_ENDPOINT
            jsonBody = '{"requestType": 0}'  // Same format as RVS request
            successMessage = "Location retrieved successfully"
            timeout = 30  // Longer timeout for location requests
            break
        default:
            log.error "Unknown command: ${command}"
            return false
    }
    
    logDebug "Executing ${command} command for ${device.label} (${method} ${endpoint})..."
    
    try {
        if (method == "POST") {
            def params = [
                uri: API_BASE_URL,
                path: endpoint,
                headers: headers,
                body: jsonBody,
                timeout: timeout
            ]
            
            httpPost(params) { response ->
                handleVehicleCommandResponse(response, device, command, successMessage, isRetry)
            }
        } else {
            def params = [
                uri: API_BASE_URL,
                path: endpoint,
                headers: headers,
                timeout: timeout
            ]
            
            httpGet(params) { response ->
                handleVehicleCommandResponse(response, device, command, successMessage, isRetry)
            }
        }
        return true
    } catch (Exception e) {
        log.error "Vehicle command '${command}' failed for ${device.label}: ${e.message}"
        
        // Check if it's a session expiry error and retry once
        if (e.message?.contains("1003") || e.message?.contains("Session Key is either invalid or expired")) {
            log.warn "Session expired during command, re-authenticating..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, retrying command..."
                return sendVehicleCommand(device, command)  // Retry once
            } else {
                log.error "Re-authentication failed after session expiry"
                return false
            }
        } else {
            return false
        }
    }
}

def handleVehicleCommandResponse(response, device, command, successMessage, isRetry = false) {
    def reCode = response.getStatus()
    def reJson = response.getData()
    
    logDebug "Vehicle command '${command}' response for ${device.label}: HTTP ${reCode}"
    
    logDebug "Command response data: ${reJson}"
    
    if (reCode == 200 && reJson.status?.statusCode == 0) {
        log.info "✅ ${successMessage} for ${device.label}"
        
        // For location command, extract and update coordinates
        if (command == "location") {
            def lat = null
            def lon = null
            
            // Try different possible location data structures (including dedicated location endpoint format)
            if (reJson.payload?.gpsDetail?.coord) {
                // Dedicated location endpoint format
                lat = reJson.payload.gpsDetail.coord.lat
                lon = reJson.payload.gpsDetail.coord.lon
                logDebug "📍 Using gpsDetail location data"
            } else if (reJson.payload?.coord) {
                // Alternative payload format
                lat = reJson.payload.coord.lat
                lon = reJson.payload.coord.lon
                logDebug "📍 Using payload.coord location data"
            } else if (reJson.coord) {
                // Direct coord format
                lat = reJson.coord.lat
                lon = reJson.coord.lon
                logDebug "📍 Using direct coord location data"
            } else if (reJson.location?.coord) {
                // Vehicle status format
                lat = reJson.location.coord.lat
                lon = reJson.location.coord.lon
                logDebug "📍 Using location.coord location data"
            }
            
            if (lat && lon) {
                try {
                    device.updateLocation(lat.toString(), lon.toString())
                    
                    // Update location attributes
                    device.sendEvent(name: "Latitude", value: lat.toString())
                    device.sendEvent(name: "Longitude", value: lon.toString())
                    device.sendEvent(name: "Location", value: "${lat}, ${lon}")
                    device.sendEvent(name: "GoogleMapsURL", value: "https://maps.google.com/maps?q=${lat},${lon}")
                    
                    // Update additional location details if available
                    if (reJson.payload?.gpsDetail?.speed) {
                        def speed = reJson.payload.gpsDetail.speed
                        def unit = speed.unit == 1 ? "mph" : "km/h"
                            device.sendEvent(name: "Speed", value: speed.value, unit: unit)
                    }
                    if (reJson.payload?.gpsDetail?.head) {
                        device.sendEvent(name: "Heading", value: reJson.payload.gpsDetail.head.toString())
                    }
                    if (reJson.payload?.gpsDetail?.coord?.alt) {
                        device.sendEvent(name: "Altitude", value: reJson.payload.gpsDetail.coord.alt, unit: "m")
                    }
                    
                    // Update home status
                    updateHomeStatus(device, lat, lon)
                    
                    log.info "📍 ✅ Updated location for ${device.label}: ${lat}, ${lon}"
                    
                    // Show additional location details if available
                    if (reJson.payload?.reportDate) {
                        logDebug "📅 Location timestamp: ${reJson.payload.reportDate.utc} (offset: ${reJson.payload.reportDate.offset})"
                    }
                    if (reJson.payload?.gpsDetail?.speed) {
                        def speed = reJson.payload.gpsDetail.speed
                        def unit = speed.unit == 1 ? "mph" : "km/h"
                        logDebug "🏃 Vehicle speed: ${speed.value} ${unit}"
                    }
                    if (reJson.payload?.gpsDetail?.head) {
                        logDebug "🧭 Vehicle heading: ${reJson.payload.gpsDetail.head}°"
                    }
                } catch (Exception e) {
                    log.error "Failed to update location: ${e.message}"
                }
            } else {
                log.warn "📍 No location coordinates found in dedicated location response"
                logDebug "Location response data: ${reJson}"
                
                // Fallback: try getting location from vehicle status
                logDebug "🔄 Fallback: Getting location from vehicle status..."
                state.locationRequestFor = device.getDeviceNetworkId()
                refreshVehicleStatus(device)
            }
        }
        
        // Only trigger a fresh status poll for commands that change vehicle state
        // Location requests already get fresh data and don't change anything
        def statusChangingCommands = ["lock", "unlock", "start", "stop", "start_charge", "stop_charge", "honk", "flash", "horn_lights", "stop_horn_lights"]
        if (statusChangingCommands.contains(command)) {
            // Check if device wants auto-refresh after commands
            def autoRefresh = device.getSetting("autoRefreshAfterCommands")
            if (autoRefresh == null || autoRefresh) {  // Default to true if not set
                def delaySeconds = device.getSetting("refreshDelaySeconds") ?: 5
                log.info "Polling vehicle directly after command execution in ${delaySeconds} seconds to verify state changes..."
                runIn(delaySeconds, "delayedFreshStatusPoll", [data: [deviceNetworkId: device.deviceNetworkId]])
            } else {
                log.info "Auto-refresh after commands is disabled for this device"
            }
        }
        
    } else {
        def errorCode = reJson.status?.errorCode
        def errorMessage = reJson.status?.errorMessage
        log.error "Vehicle command '${command}' failed for ${device.label}: Error ${errorCode} - ${errorMessage}"
        
        // For location command, fallback to vehicle status if dedicated endpoint fails
        if (command == "location") {
            logDebug "🔄 Location endpoint failed, trying vehicle status fallback..."
            state.locationRequestFor = device.getDeviceNetworkId()
            refreshVehicleStatus(device)
            return // Don't continue with normal retry logic
        }
        
        // Check for specific error codes and handle appropriately
        if (errorCode == 1003 && !isRetry) {
            log.warn "Session expired during command, re-authenticating and retrying..."
            
            // Clear the expired session
            state.authenticated = false
            state.sessionId = null
            
            // Re-authenticate
            authenticate()
            
            if (state.authenticated) {
                log.info "Re-authentication successful, refreshing vehicle data and retrying ${command} command..."
                // Refresh vehicle discovery to get updated vehicle keys for the new session
                discoverVehicles()
                // Give the device data update a moment to complete, then retry
                runIn(2, "retryVehicleCommand", [data: [deviceNetworkId: device.deviceNetworkId, command: command]])
            } else {
                log.error "Re-authentication failed after session expiry"
            }
        } else if (errorCode == 1003 && isRetry) {
            log.error "Session expired again on retry - giving up on ${command} command"
        } else if (errorCode == 1005) {
            if (!isRetry) {
                log.warn "Invalid vehicle error - vehicle key may be stale for current session, refreshing vehicle data and retrying..."
                // Refresh vehicle discovery to get updated vehicle keys
                discoverVehicles()
                // Give the device data update a moment to complete, then retry
                runIn(2, "retryVehicleCommand", [data: [deviceNetworkId: device.deviceNetworkId, command: command]])
            } else {
                log.error "Vehicle key still invalid after refresh - ${command} command failed permanently"
            }
        }
    }
}

def delayedStatusRefresh(data) {
    log.info "Refreshing status after command execution..."
    def device = getChildDevice(data.deviceNetworkId)
    if (device) {
        getVehicleStatus(device)
    }
}

def delayedFreshStatusPoll(data) {
    log.info "Polling vehicle directly after command execution to verify state changes..."
    def device = getChildDevice(data.deviceNetworkId)
    if (device) {
        refreshVehicleStatus(device)
    }
}

def retryVehicleCommand(data) {
    log.info "Retrying vehicle command '${data.command}' with fresh vehicle keys..."
    def device = getChildDevice(data.deviceNetworkId)
    if (device) {
        sendVehicleCommand(device, data.command, true)
    } else {
        log.error "Device not found for retry: ${data.deviceNetworkId}"
    }
}

def retryVehicleStatusRefresh(data) {
    log.info "Retrying vehicle status refresh with fresh vehicle keys..."
    def device = getChildDevice(data.deviceNetworkId)
    if (device) {
        refreshVehicleStatus(device, true)
    } else {
        log.error "Device not found for retry: ${data.deviceNetworkId}"
    }
}

def buildClimateRequestBody(temp, duration, defrost, heatedSteering, heatedSeats, cooledSeats) {
    // Build climate control request body using exact format from Python library
    def json = '{'
    json += '"remoteClimate": {'
    json += '"airTemp": {"unit": 1, "value": "' + temp + '"},'
    json += '"airCtrl": true,'
    json += '"defrost": ' + defrost + ','
    json += '"heatingAccessory": {'
    json += '"rearWindow": 0,'  // Rear window defrost
    json += '"sideMirror": 0,'  // Side mirror heating  
    json += '"steeringWheel": ' + (heatedSteering ? '1' : '0') + ','
    json += '"steeringWheelStep": ' + (heatedSteering ? '1' : '0')
    json += '},'
    json += '"ignitionOnDuration": {"unit": 4, "value": ' + duration + '}'
    
    // Add seat heating/cooling if enabled (using empirical values from Python library)
    if (heatedSeats || cooledSeats) {
        json += ', "heatVentSeat": {'
        json += '"driverSeat": ' + getSeatSettings(heatedSeats, cooledSeats) + ','
        json += '"passengerSeat": ' + getSeatSettings(heatedSeats, cooledSeats) + ','
        json += '"rearLeftSeat": {"heatVentType": 0, "heatVentLevel": 1, "heatVentStep": 0},'
        json += '"rearRightSeat": {"heatVentType": 0, "heatVentLevel": 1, "heatVentStep": 0}'
        json += '}'
    }
    
    json += '}}'
    return json
}

def getSeatSettings(heatedSeats, cooledSeats) {
    // Return seat settings based on Python library empirical values
    if (heatedSeats && cooledSeats) {
        // If both are enabled, prioritize heating (medium level)
        return '{"heatVentType": 1, "heatVentLevel": 2, "heatVentStep": 1}'
    } else if (heatedSeats) {
        // Medium heat level
        return '{"heatVentType": 1, "heatVentLevel": 2, "heatVentStep": 1}'
    } else if (cooledSeats) {
        // Medium cooling level (type 2 = cooling)
        return '{"heatVentType": 2, "heatVentLevel": 2, "heatVentStep": 1}'
    } else {
        // Off
        return '{"heatVentType": 0, "heatVentLevel": 1, "heatVentStep": 0}'
    }
}

def calculateDistance(lat1, lon1, lat2, lon2) {
    // Calculate distance between two points using Haversine formula
    // Returns distance in meters
    
    if (!lat1 || !lon1 || !lat2 || !lon2) {
        return null
    }
    
    def R = 6371000 // Earth's radius in meters
    def dLat = Math.toRadians(lat2 - lat1)
    def dLon = Math.toRadians(lon2 - lon1)
    def a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon/2) * Math.sin(dLon/2)
    def c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    def distance = R * c
    
    return distance
}

def updateHomeStatus(device, vehicleLat, vehicleLon) {
    // Check if vehicle is at home based on device settings
    def homeLat = device.getSetting("homeLatitude")
    def homeLon = device.getSetting("homeLongitude")
    def homeRadius = device.getSetting("homeRadius") ?: 100
    
    if (!homeLat || !homeLon) {
        // Home location not configured
        device.sendEvent(name: "isHome", value: "Unknown")
        return
    }
    
    def distance = calculateDistance(vehicleLat as Double, vehicleLon as Double, homeLat as Double, homeLon as Double)
    
    if (distance != null) {
        def isAtHome = distance <= homeRadius
        device.sendEvent(name: "isHome", value: isAtHome ? "true" : "false")
        
        if (isAtHome) {
            log.info "🏠 ${device.label} is at home (${Math.round(distance)}m from home)"
        } else {
            log.info "🚗 ${device.label} is away from home (${Math.round(distance)}m from home)"
        }
    } else {
        device.sendEvent(name: "isHome", value: "Unknown")
        log.warn "Unable to calculate distance to home for ${device.label}"
    }
}

// ====================
// UTILITIES
// ====================

def generateDeviceId() {
    def chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    def urlSafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    
    def random = new Random()
    
    // Generate 22 random alphanumeric characters
    def part1 = ""
    for (int i = 0; i < 22; i++) {
        part1 += chars.charAt(random.nextInt(chars.length()))
    }
    
    // Generate 140 random URL-safe characters
    def part2 = ""
    for (int i = 0; i < 140; i++) {
        part2 += urlSafeChars.charAt(random.nextInt(urlSafeChars.length()))
    }
    
    return part1 + ":" + part2
}