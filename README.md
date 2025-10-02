# Hubitat Kia Connect

**Kia UVO Connect Integration for Hubitat Elevation**

_This application and driver is released under the M.I.T. license. Use at your own risk_

## What is it?

Kia UVO Connect is a connected vehicle service that remotely collects telemetrics and allows users to issue commands to vehicles that support the service. This Hubitat integration interfaces with the Kia UVO Connect web services to enable remote vehicle control and monitoring directly from your smart home hub.

## Features

**Remote Vehicle Control:**
- 🔐 Lock/unlock doors
- 🌡️ Start/stop climate control with configurable parameters
- ⚡ Start/stop EV charging
- 📍 Get current location with Google Maps integration
- 🔊 Horn and lights activation/deactivation
- 🏠 Home location detection with customizable radius

**Vehicle Monitoring:**
- 🔋 Battery level and charging status (for EVs)
- ⛽ Fuel level (for ICE vehicles)
- 🚗 Vehicle status (doors, engine, climate, etc.)
- 📊 Comprehensive HTML status display
- 📍 GPS coordinates with speed, heading, and altitude
- ⏰ Charging time remaining in multiple formats
- 🕐 Estimated charge completion timestamp

**Smart Features:**
- 🔄 Automatic session management with retry logic
- 📱 Configurable auto-refresh after commands
- 🏡 Home/away detection based on GPS location
- 🔧 Per-vehicle climate control settings
- 📝 Configurable debug logging with auto-disable
- 🚨 Error events for automation triggers

## Supported Vehicles

**Tested Models:**
- Kia EV6
- Kia EV9
- Other Kia vehicles with UVO Connect (US market)

**Requirements:**
- Kia vehicle with UVO Connect service (2019+)
- Active UVO Connect subscription
- US market vehicle (other regions not currently supported)

## Installation

### 1. Install the Code

**Install the Driver:**
1. Go to **Hubitat Web Interface** → **Drivers Code**
2. Click **New Driver**
3. Copy and paste the contents of `KiaUVODriver.groovy`
4. Click **Save**

**Install the App:**
1. Go to **Hubitat Web Interface** → **Apps Code**
2. Click **New App**
3. Copy and paste the contents of `KiaUVOApp.groovy`
4. Click **Save**

### 2. Configure the App

1. Go to **Apps** → **Add User App**
2. Select **Kia UVO Connect**
3. Enter your Kia UVO Connect credentials
4. Click **Authenticate & Discover Vehicles**
5. Your vehicles will be automatically discovered and created as devices

### 3. Configure Vehicle Settings

For each vehicle device:
1. Go to the device page
2. Configure climate control preferences (temperature, duration, accessories)
3. Set home location coordinates and radius
4. Adjust auto-refresh settings as desired

## Usage

### Basic Commands

**From Device Page:**
- **Refresh** - Get cached vehicle status (fast)
- **Poll Vehicle** - Get fresh status directly from vehicle (slower, more accurate)
- **Lock/Unlock** - Control door locks
- **Start/Stop Climate** - Control climate system with your configured settings
- **Start/Stop Charge** - Control EV charging (EV models only)
- **Horn and Lights** - Activate horn and lights
- **Stop Horn and Lights** - Deactivate horn and lights
- **Get Location** - Force location update

### Automation Integration

**Available Attributes for Rules:**
- `BatterySoC` / `battery` - Battery percentage
- `FuelLevel` - Fuel level percentage  
- `EVRange` - Electric range in miles
- `ChargingStatus` - "Charging" or "Not Charging"
- `DoorLock` - "locked" or "unlocked"
- `AirControl` - Climate control status
- `isHome` - true/false based on home location
- `Latitude` / `Longitude` - GPS coordinates
- `EstimatedChargeCompletionTime` - ISO timestamp for automations

**Example Rule Machine Uses:**
- Send notification when charging completes
- Turn on garage lights when vehicle arrives home
- Start climate control before departure time
- Alert if vehicle is left unlocked away from home

## Configuration Options

### App Settings
- **Username/Password** - Your Kia UVO Connect credentials
- **Debug Logging** - Enable detailed logging for troubleshooting
- **Auto-disable Debug** - Automatically turn off debug logging after specified minutes

### Device Settings
- **Climate Control** - Temperature, duration, heated accessories
- **Home Location** - Latitude, longitude, and radius for home detection
- **Auto Refresh** - Enable automatic status updates after commands
- **Refresh Delay** - Time to wait before auto-refresh (seconds)

## Troubleshooting

### Common Issues

**Authentication Failures:**
- Verify your UVO Connect credentials
- Ensure your account doesn't have 2FA enabled
- Check that your vehicle is properly registered with UVO Connect

**Commands Not Working:**
- Try the "Poll Vehicle" command to get fresh status
- Check debug logs for specific error messages
- Ensure your vehicle has cellular connectivity

**Stale Data:**
- Use "Poll Vehicle" instead of "Refresh" for real-time data
- Increase the auto-refresh delay if commands seem to conflict

### Debug Logging

Enable debug logging in the app settings for detailed troubleshooting information. Debug logging will automatically disable after the configured time period to prevent log spam.

## Technical Details

**API Endpoints:** Uses official Kia UVO Connect web services
**Authentication:** OAuth-style authentication with session management
**Rate Limiting:** Built-in delays to respect API limits
**Error Handling:** Automatic retry logic for transient failures
**Security:** Credentials stored securely in Hubitat's encrypted database

## Limitations

1. **US Market Only** - Currently supports US Kia vehicles only
2. **UVO Connect Required** - Vehicle must have active UVO Connect subscription
3. **API Dependency** - Based on undocumented APIs that could change
4. **Network Dependent** - Requires vehicle to have cellular connectivity
5. **Rate Limits** - Excessive API calls may result in account restrictions

## Credits

**Development:**
- Built using reverse-engineered API calls from the `hyundai_kia_connect_api` Python library
- Inspired by the Bluelinky Node.js project
- Original Hyundai Bluelink integration by Tim Yuhl (@WindowWasher)

**Special Thanks:**
- @Hacksore and the Bluelinky team for API research
- The `hyundai_kia_connect_api` Python library maintainers
- Hubitat community for testing and feedback

## Support

**Reporting Issues:**
- Provide detailed steps to reproduce the problem
- Include relevant log entries (with debug logging enabled)
- **Never share your UVO Connect credentials in public forums**

**Feature Requests:**
- Submit via GitHub issues
- Include specific use cases and benefits

---

**⚠️ Disclaimer:** This integration uses unofficial APIs that could change or break at any time. Use at your own risk and don't become overly dependent on this integration for critical vehicle functions.