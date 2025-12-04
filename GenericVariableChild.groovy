/**
 * Generic Variable Child Driver
 * 
 * A simple child device driver that implements the Variable capability.
 * Can be used for any variable that needs to be set from a dashboard or automation.
 * 
 * Author: Jeremy Akers
 * Version: 1.0.0
 */

metadata {
    definition(
        name: "Generic Variable Child",
        namespace: "kia-uvo",
        author: "Jeremy Akers",
        importUrl: "https://raw.githubusercontent.com/jeremyakers/Hubitat-Kia-Connect/main/GenericVariableChild.groovy"
    ) {
        capability "Variable"
        
        attribute "variable", "string"
        
        command "setVariable", [[name: "valueToSet", type: "STRING"]]
    }
}

def installed() {
    log.info "Generic Variable Child installed: ${device.label}"
    initialize()
}

def updated() {
    log.info "Generic Variable Child updated: ${device.label}"
    initialize()
}

def initialize() {
    // Set default value if not already set
    if (!device.currentValue("variable")) {
        sendEvent(name: "variable", value: "")
    }
}

def setVariable(valueToSet) {
    log.info "Setting variable '${device.label}' to: ${valueToSet}"
    sendEvent(name: "variable", value: valueToSet)
    
    // Notify parent device if this is a component
    if (device.isComponent && parent) {
        parent.componentVariableChanged(device, valueToSet)
    }
}

