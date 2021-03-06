/*
	Logic-Group Matrix ZDB5100
    This driver is nothing near finished, but can be used as a starting point for further development

	Based on https://github.com/hubitat/HubitatPublic/blob/master/examples/drivers/basicZWaveTool.groovy

	WARNING!
		--Setting device parameters is an advanced feature, randomly poking values to a device
		can lead to unexpected results which may result in needing to perform a factory reset 
		or possibly bricking the device
		--Refer to the device documentation for the correct parameters and values for your specific device
		--Hubitat cannot be held responsible for misuse of this tool or any unexpected results generated by its use
*/

import groovy.transform.Field

metadata {
    definition (name: "Logic-Group Matrix ZDB5100",namespace: "steenole", author: "Steen Ole Andersen") {
        
    capability "PushableButton"
    capability "HoldableButton"
    capability "ReleasableButton"
    capability "DoubleTapableButton"
        
    command "push", ["NUMBER"]
    //command "cleanup"
        
	command "getCommandClassReport"
	command "getParameterReport", [[name:"parameterNumber",type:"NUMBER", description:"Parameter Number (omit for a complete listing of parameters that have been set)", constraints:["NUMBER"]]]
	command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],[name:"size",type:"NUMBER", description:"Parameter Size", constraints:["NUMBER"]],[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]]
		
    }
}

@Field Map zwLibType = [
	0:"N/A",1:"Static Controller",2:"Controller",3:"Enhanced Slave",4:"Slave",5:"Installer",
	6:"Routing Slave",7:"Bridge Controller",8:"Device Under Test (DUT)",9:"N/A",10:"AV Remote",11:"AV Device"
]

void parse(String description) {
    hubitat.zwave.Command cmd = zwave.parse(description,[0x85:1,0x86:2])
    if (cmd) {
        zwaveEvent(cmd)
    }
}

//Z-Wave responses
void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    log.info "ConfigurationReport- parameterNumber:${cmd.parameterNumber}, size:${cmd.size}, value:${cmd.scaledConfigurationValue}"
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionCommandClassReport cmd) {
    log.info "CommandClassReport- class:${ "0x${intToHexStr(cmd.requestedCommandClass)}" }, version:${cmd.commandClassVersion}"		
}	

String zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand()
    if (encapCmd) {
		return zwaveEvent(encapCmd)
    } else {
        log.warn "Unable to extract encapsulated cmd from ${cmd}"
    }
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd){
    if (logEnable) log.debug "CentralSceneNotification: ${cmd}"

    Integer button = cmd.sceneNumber
    Integer key = cmd.keyAttributes
    String action
    switch (key){
        case 0: //pushed
            action = "pushed"
            break
        case 1:	//released, only after 2
            state."${button}" = 0
            action = "released"
            break
        case 2:	//holding
            if (state."${button}" == 0){
                state."${button}" = 1
                runInMillis(200,delayHold,[data:button])
            }
            break
        case 3:	//double tap, 4 is tripple tap
            action = "doubleTapped"
            break
    }

    if (action){
        sendButtonEvent(action, button, "physical")
    }
}

//void cleanup(){
//    state.remove("4")
//    state.remove("lastSequenceNumber")
//}

void delayHold(button){
    sendButtonEvent("held", button, "physical")
}

void push(button){
    sendButtonEvent("pushed", button, "digital")
}

void hold(button){
    sendButtonEvent("held", button, "digital")
}

void release(button){
    sendButtonEvent("released", button, "digital")
}

void doubleTap(button){
    sendButtonEvent("doubleTapped", button, "digital")
}

void sendButtonEvent(action, button, type){
    String descriptionText = "${device.displayName} button ${button} was ${action} [${type}]"
    if (txtEnable) log.info descriptionText
    sendEvent(name:action, value:button, descriptionText:descriptionText, isStateChange:true, type:type)
}

void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand()
    log.debug "Encapsulated cmd on endpoint ${cmd.sourceEndPoint}: ${encapCmd}"
}
void zwaveEvent(hubitat.zwave.Command cmd) {
    log.debug "skip: ${cmd}"
}

//cmds
List<String> setParameter(parameterNumber = null, size = null, value = null){
    if (parameterNumber == null || size == null || value == null) {
		log.warn "incomplete parameter list supplied..."
		log.info "syntax: setParameter(parameterNumber,size,value)"
    } else {
		return delayBetween([
	    	secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: parameterNumber, size: size)),
	    	secureCmd(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
		],500)
    }
}

List<String> getParameterReport(param = null){
    List<String> cmds = []
    if (param != null) {
		cmds = [secureCmd(zwave.configurationV1.configurationGet(parameterNumber: param))]
    } else {
		0.upto(255, {
	    	cmds.add(secureCmd(zwave.configurationV1.configurationGet(parameterNumber: it)))	
		})
    }
    log.trace "configurationGet command(s) sent..."
    return delayBetween(cmds,500)
}	

List<String> getCommandClassReport(){
    List<String> cmds = []
    List<Integer> ic = getDataValue("inClusters").split(",").collect{ hexStrToUnsignedInt(it) }
    ic.each {
		if (it) cmds.add(secureCmd(zwave.versionV1.versionCommandClassGet(requestedCommandClass:it)))
    }
    return delayBetween(cmds,500)
}

String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd)
}

void installed(){}

void configure() {}

void updated() {}

String secureCmd(cmd) {
    if (getDataValue("zwaveSecurePairingComplete") == "true" && getDataValue("S2") == null) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
		return secure(cmd)
    }	
}