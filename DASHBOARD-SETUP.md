# Kia UVO Connect - Dashboard Setup Guide

This guide explains how to set up Hubitat dashboards using the child device approach for climate control.

## Table of Contents
- [Overview](#overview)
- [Child Devices Created](#child-devices-created)
- [Dashboard Configuration](#dashboard-configuration)
- [Climate Control Workflow](#climate-control-workflow)
- [Dashboard Tile Configuration](#dashboard-tile-configuration)
- [CSS Styling for HTML Tiles](#css-styling-for-html-tiles)
- [Troubleshooting](#troubleshooting)

## Overview

The Kia UVO Connect integration uses a **passive child device approach** for climate control settings. This means:
- Child devices store climate settings (temperature, defrost, steering wheel, seats)
- Settings can be configured through dashboard tiles
- No API calls are made when changing settings
- The **Start Climate** command on the parent device reads all settings and sends one consolidated API request

### Why This Approach?

Traditional thermostat tiles trigger climate start immediately when you change modes, preventing you from configuring other settings first. The passive approach allows you to:
1. Set your desired temperature
2. Configure defrost options
3. Adjust steering wheel heating
4. Set seat heating/cooling preferences
5. Then start climate with all settings applied at once

## Child Devices Created

When you initialize a Kia vehicle device, it automatically creates child devices:

### Always Created (All Vehicles)

1. **`[Vehicle Name] - Front Defrost`**
   - Type: Virtual Switch
   - Purpose: Enable/disable front windshield defrost
   - Dashboard Tile: Switch

2. **`[Vehicle Name] - Rear Defrost`**
   - Type: Virtual Switch
   - Purpose: Enable/disable rear window defrost
   - Dashboard Tile: Switch

3. **`[Vehicle Name] - Heated Steering Wheel`**
   - Type: Virtual Fan Controller
   - Purpose: Set steering wheel heating level
   - Supported Speeds:
     - **2022-2023 models**: `off`, `on`
     - **2024+ models**: `off`, `low`, `high`
   - Dashboard Tile: Fan speed control

### Created When "Enable heated/cooled seats" is ON

4. **`[Vehicle Name] - Driver Seat`**
   - Type: Kia Climate Seat Control
   - Purpose: Control driver seat heating/cooling
   - Supported Modes: `off`, `heat`, `cool`
   - Supported Levels: `low`, `medium`, `high`
   - Dashboard Tiles: Thermostat mode + Fan speed

5. **`[Vehicle Name] - Passenger Seat`**
   - Same as driver seat

6. **`[Vehicle Name] - Rear Left Seat`**
   - Type: Kia Climate Seat Control
   - Purpose: Control rear left seat heating
   - Supported Modes: `off`, `heat` (no cooling for rear seats)
   - Supported Levels: `low`, `medium`, `high`
   - Dashboard Tiles: Thermostat mode + Fan speed

7. **`[Vehicle Name] - Rear Right Seat`**
   - Same as rear left seat

## Dashboard Configuration

### Step 1: Add Parent Device Tiles

Add these tiles for the main vehicle device:

1. **Vehicle Info** (HTML Attribute)
   - Attribute: `vehicleInfoHtml`
   - Shows: Model, year, odometer

2. **Battery Status** (HTML Attribute)
   - Attribute: `batteryHtml`
   - Shows: Battery SoC, range, 12V battery

3. **Charging Status** (HTML Attribute)
   - Attribute: `chargingHtml`
   - Shows: Charging status, power, time remaining, completion time

4. **Doors & Security** (HTML Attribute)
   - Attribute: `doorsSecurityHtml`
   - Shows: Lock status, door/window/trunk positions

5. **Location** (HTML Attribute)
   - Attribute: `locationHtml`
   - Shows: GPS coordinates, home status, heading, altitude

6. **Climate On/Off** (Switch)
   - Shows current climate status
   - Toggle to start/stop climate control
   - **This is the main action button - turns on climate with all configured settings**

7. **Temperature Setpoint** (Attribute - number)
   - Attribute: `thermostatSetpoint`
   - Allows setting desired temperature
   - Does NOT start climate automatically

8. **Battery Level** (Battery)
   - Shows battery percentage with icon

9. **Lock/Unlock** (Lock)
   - Control door locks

10. **Refresh** (Command Button)
    - Command: `pollVehicle`
    - Manually refresh vehicle status

### Step 2: Add Climate Control Child Device Tiles

#### Always Add (All Vehicles):

11. **Front Defrost** (Switch)
    - Device: `[Vehicle Name] - Front Defrost`
    - Toggle defrost on/off

12. **Rear Defrost** (Switch)
    - Device: `[Vehicle Name] - Rear Defrost`
    - Toggle defrost on/off

13. **Heated Steering Wheel** (Fan Speed)
    - Device: `[Vehicle Name] - Heated Steering Wheel`
    - Set heating level (off/on or off/low/high depending on model year)

#### Add If Seat Control is Enabled:

14. **Driver Seat Mode** (Thermostat Mode)
    - Device: `[Vehicle Name] - Driver Seat`
    - Attribute: `thermostatMode`
    - Select: Off, Heat, or Cool

15. **Driver Seat Level** (Fan Speed)
    - Device: `[Vehicle Name] - Driver Seat`
    - Attribute: `speed`
    - Select: Low, Medium, or High

16. **Passenger Seat Mode** (Thermostat Mode)
    - Device: `[Vehicle Name] - Passenger Seat`

17. **Passenger Seat Level** (Fan Speed)
    - Device: `[Vehicle Name] - Passenger Seat`

18. **Rear Left Seat Mode** (Thermostat Mode)
    - Device: `[Vehicle Name] - Rear Left Seat`
    - Only Heat mode available (no cooling)

19. **Rear Left Seat Level** (Fan Speed)
    - Device: `[Vehicle Name] - Rear Left Seat`

20. **Rear Right Seat Mode** (Thermostat Mode)
    - Device: `[Vehicle Name] - Rear Right Seat`

21. **Rear Right Seat Level** (Fan Speed)
    - Device: `[Vehicle Name] - Rear Right Seat`

### Step 3: Optional Additional Tiles

- **Presence** - Shows if vehicle is home/away
- **Contact Sensor** - Shows if any door/window is open
- **Location (Attribute)** - Text display of GPS coordinates
- **Google Maps URL** - Link to vehicle location on map
- **Odometer** - Current mileage
- **Charging Power** - Current charging rate (kW)
- **Plug Status** - Connected/disconnected

## Climate Control Workflow

### Basic Usage (No Seat Control)

1. Set desired temperature using the temperature setpoint
2. Toggle front/rear defrost switches if needed
3. Set steering wheel heating level (off/on/low/high)
4. Click the main **Switch "ON"** to start climate
5. Climate starts with all configured settings

### Advanced Usage (With Seat Control)

1. Set desired temperature
2. Configure defrost switches
3. Set steering wheel heating
4. For each seat you want to heat/cool:
   - Select mode (Off/Heat/Cool)
   - Select level (Low/Medium/High)
5. Click the main **Switch "ON"** to start climate
6. All settings are sent in one API request

### Stopping Climate

- Click the main **Switch "OFF"** to stop climate control
- This sends a stop command to the vehicle

### Important Notes

- **Settings are passive**: Changing any setting does NOT trigger an API call
- **Only the Switch triggers the API**: The main Switch "ON" is what actually starts climate
- **All settings are read when starting**: When you start climate, the driver reads all child device states
- **One API call**: All settings are sent together in a single request to minimize vehicle wake-ups

## Dashboard Tile Configuration

### Recommended Layout

```
┌─────────────────────────────────────────┐
│  Vehicle Info  │  Battery   │  Charging │
├─────────────────────────────────────────┤
│  Doors/Security│  Location  │  Lock     │
├─────────────────────────────────────────┤
│  Climate ON/OFF│  Temp (72°)│  Refresh  │
├─────────────────────────────────────────┤
│  Front Defrost │ Rear Defrost│ Steering │
├─────────────────────────────────────────┤
│  Driver Mode   │ Driver Level│ Pass Mode│
├─────────────────────────────────────────┤
│  Pass Level    │ Rear L Mode │ Rear L Lvl│
└─────────────────────────────────────────┘
```

### Tile Size Recommendations

- **HTML Attributes** (vehicle/battery/charging/doors/location): 2x2 or 2x3
- **Switch (Climate ON/OFF)**: 1x1
- **Temperature Setpoint**: 1x1
- **Defrost Switches**: 1x1 each
- **Steering Wheel Fan**: 1x1
- **Seat Mode**: 1x1 each
- **Seat Level**: 1x1 each

## CSS Styling for HTML Tiles

The HTML status tiles use CSS classes that can be styled at the dashboard level for a consistent look.

### Available CSS Classes

- `.kia-status` - Main container
- `.kia-status h3` - Section headers
- `.kia-status table` - Data tables
- `.kia-status td` - Table cells
- `.kia-label` - Bold labels
- `.kia-link` - Links (e.g., Google Maps)
- `.kia-home` - Home status (green)
- `.kia-away` - Away status (gray)

### Example Custom CSS

To add custom styling to your dashboard:

1. Go to your dashboard settings
2. Scroll to "Advanced" → "CSS"
3. Add custom styles:

```css
/* Kia Status Tiles */
.kia-status {
    font-family: Arial, sans-serif;
    font-size: 14px;
    background: #1a1a1a;
    border-radius: 8px;
    padding: 12px;
}

.kia-status h3 {
    color: #4a9eff;
    margin: 0 0 10px;
    font-size: 16px;
    border-bottom: 2px solid #4a9eff;
    padding-bottom: 5px;
}

.kia-status table {
    width: 100%;
    border-collapse: collapse;
}

.kia-status td {
    padding: 4px 6px;
    border-bottom: 1px solid #333;
}

.kia-label {
    font-weight: bold;
    color: #ccc;
}

.kia-link {
    color: #4a9eff;
    text-decoration: none;
}

.kia-link:hover {
    text-decoration: underline;
}

.kia-home {
    color: #28a745;
    font-weight: bold;
}

.kia-away {
    color: #6c757d;
}
```

Reference: See `KiaUVODashboard.css` for a complete example stylesheet.

## Troubleshooting

### Child Devices Not Created

**Problem**: Child devices aren't showing up after initialization.

**Solution**:
1. Go to the parent device page
2. Click the "Initialize" command button
3. Check the device logs for creation messages
4. Verify the driver code is up to date

### Supported Fan Speeds Not Showing Correctly

**Problem**: Steering wheel shows wrong options (on/medium/medium-high/high instead of off/on or off/low/high).

**Solution**:
1. Go to the parent device page
2. Click "Save Preferences" or "Initialize" to refresh child devices
3. This will set the correct `supportedFanSpeeds` attribute based on your model year

### Seat Controls Not Created

**Problem**: Seat child devices aren't created even though vehicle supports them.

**Solution**:
1. Go to parent device preferences
2. Enable "Enable heated/cooled seat control"
3. Click "Save Preferences"
4. Child devices will be created automatically

### Seat Controls Need to be Removed

**Problem**: Seat controls created but vehicle doesn't support them (causes API errors).

**Solution**:
1. Go to parent device preferences
2. Disable "Enable heated/cooled seat control"
3. Click "Save Preferences"
4. Seat child devices will be automatically removed

### Climate Starts But Wrong Settings Applied

**Problem**: Climate starts but temperature, defrost, or other settings are wrong.

**Solution**:
1. Verify settings are configured BEFORE clicking Switch "ON"
2. Check child device current states match your desired settings
3. Try setting each child device manually, then starting climate
4. Check driver logs for "Starting climate:" message to see what values were sent

### Temperature Not Adjustable on Dashboard

**Problem**: Can't adjust temperature from dashboard tile.

**Solution**:
- The parent device only has a `thermostatSetpoint` attribute (not full Thermostat capability)
- Use an **Attribute tile** with the `thermostatSetpoint` attribute
- Some dashboard themes may not support editing number attributes directly
- Alternative: Use the device preferences to set default `climateTemp`

### HTML Tiles Show "undefined" or Blank

**Problem**: HTML attribute tiles are empty or show "undefined".

**Solution**:
1. Force a vehicle refresh using the "Refresh" or "Poll Vehicle" command
2. Wait 10-30 seconds for the status to update
3. If still blank, check driver logs for errors during status update

### Climate Command Fails

**Problem**: Climate start command returns an error or fails silently.

**Solution**:
1. Check app logs for error codes (1003, 1005, 9001)
2. Error 1003: Session expired - app will re-authenticate automatically
3. Error 1005: Invalid vehicle key - app will refresh vehicle list and retry
4. Error 9001: Vehicle rejected command - may not support certain features
5. If using seat controls on a vehicle that doesn't support them, disable the preference

### Child Devices Don't Trigger Climate Start

**Problem**: Changing child device settings doesn't start climate.

**Solution**:
- This is expected behavior! Child devices are **passive**
- They only store settings without triggering API calls
- You must explicitly click the main Switch "ON" to start climate
- This design prevents unwanted API calls and allows full configuration before starting

## Additional Resources

- **Main README**: See `README.md` for installation and setup instructions
- **Debug Folder**: Contains API response examples and setup notes
- **GitHub Repository**: [https://github.com/jeremyakers/Hubitat-Kia-Connect](https://github.com/jeremyakers/Hubitat-Kia-Connect)
- **Hubitat Community**: Search for "Kia UVO Connect" for community support

## Version History

- **v1.0.0** (December 2025)
  - Initial release with child device support
  - Passive climate control configuration
  - Separate HTML attributes for dashboard tiles
  - Support for heated/cooled seats and steering wheel

