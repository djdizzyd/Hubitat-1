/**
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.Field

@Field String VERSION = "1.0.0"

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]

metadata {
  definition (name: "Z-Wave Range Extender", namespace: "syepes", author: "Sebastian YEPES") {
    capability "Polling"
    capability "Refresh"
    capability "Configuration"
    capability "Initialize"

    command "clearState"
    command "deviceCommTest"

    attribute "status", "string"

    fingerprint mfr: "0246", prod: "0001", model: "0001", deviceJoinName: "Iris Z-Wave Range Extender (Smart Plug)"
    fingerprint mfr: "021F", prod: "0003", model: "0108", deviceJoinName: "Dome Range Extender DMEX1" //US
    fingerprint mfr: "0086", prod: "0104", model: "0075", deviceJoinName: "Aeotec Range Extender 6" //US
    fingerprint mfr: "0086", prod: "0204", model: "0075", deviceJoinName: "Aeotec Range Extender 6" //UK, AU
    fingerprint mfr: "0086", prod: "0004", model: "0075", deviceJoinName: "Aeotec Range Extender 6" //EU
    fingerprint mfr: "0371", prod: "0104", model: "00BD", deviceJoinName: "Aeotec Range Extender 7" //US
    fingerprint mfr: "0371", prod: "0004", model: "00BD", deviceJoinName: "Aeotec Range Extender 7" //EU
    fingerprint deviceId: "117", inClusters: "0x5E, 0x26, 0x33, 0x70, 0x85, 0x59, 0x72, 0x86, 0x7A, 0x73, 0x5A"

  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL
    }
  }
}

def installed() {
  logger("debug", "installed(${VERSION})")

  if (state.deviceInfo == null) {
    state.deviceInfo = [:]
  }
  state.driverVer = VERSION

  initialize()
}

def initialize() {
  logger("debug", "initialize()")
}

def updated() {
  logger("debug", "updated()")

  if (!state.driverVer || state.driverVer != VERSION) {
    installed()
  }

  if (!state.MSR) {
    refresh()
  }

  unschedule()
  configure()
}

def poll() {
  logger("debug", "poll()")

  secureSequence([
    zwave.powerlevelV1.powerlevelGet(),
    zwave.versionV1.versionGet(),
    zwave.firmwareUpdateMdV2.firmwareMdGet(),
    zwave.manufacturerSpecificV2.manufacturerSpecificGet()
  ])
}

def refresh() {
  logger("debug", "refresh() - state: ${state.inspect()}")

  secureSequence([
    zwave.powerlevelV1.powerlevelGet(),
    zwave.versionV1.versionGet(),
    zwave.firmwareUpdateMdV2.firmwareMdGet(),
    zwave.manufacturerSpecificV2.manufacturerSpecificGet(),
    zwave.associationGrpInfoV1.associationGroupNameGet(groupingIdentifier: 1),
    zwave.associationV2.associationGet(groupingIdentifier: 1)
  ])
}

def clearState() {
  logger("debug", "ClearStates() - Clearing device states")

  state.clear()

  if (state.deviceInfo == null) {
    state.deviceInfo = [:]
  } else {
    state.deviceInfo.clear()
  }
}

def devicePoll() {
  logger("debug", "devicePoll()")

  if (state.devicePings >= 3) {
    if (device.currentValue('status') != 'offline') {
      sendEvent([ name: "status", value: 'offline', descriptionText: "${device.displayName} is offline", isStateChange: true, displayed: true])
    }
    logger("warn", "Device is offline")
  }

  state.devicePings = state.devicePings + 1

  secure(zwave.powerlevelV1.powerlevelGet())
}

def deviceUpdate() {
  // Sets the device status to online, but only if previously was offline
  Map deviceState = [ name: "status",
                      value: 'online',
                      descriptionText: "$device.displayName is online",
                      isStateChange: (device.currentValue('status') != 'online' ? true : false),
                      displayed: (device.currentValue('status') != 'online' ? true : false)
  ]

  state.devicePings = 0
  logger("info", "Device is online")

  sendEvent(deviceState)
}

def configure() {
  logger("debug", "configure()")

  state.devicePings = 0
  state.deviceCommTests = 0

  schedule("0 0/5 * * * ?", devicePoll)

  // Associate Group 1 (Lifeline) with the Hub.
  secure(zwave.associationV2.associationSet(groupingIdentifier:1, nodeId: zwaveHubNodeId))
}

def parse(String description) {
  logger("debug", "parse() - description: ${description.inspect()}")

  def result = []
  if (description != "updated") {
    def cmd = zwave.parse(description, commandClassVersions)
    if (cmd) {
      result = zwaveEvent(cmd)
      logger("debug", "parse() - description: ${description.inspect()} to cmd: ${cmd.inspect()} with result: ${result.inspect()}")

    } else {
      logger("error", "parse() - Non-parsed - description: ${description?.inspect()}")
    }
  }

  result
}

/*
  Executes a Z-Wave powerlevel test with 10 transmission frames.
  Note: Useful for testing Z-Wave signal reliability
*/
def deviceCommTest() {
  logger("trace", "deviceCommTest()")
  state.deviceCommTests = 0

  runIn(10, commTestResults)

  // Test 30 frames at nominal power
  sendHubCommand(new hubitat.device.HubAction(zwave.powerlevelV1.powerlevelTestNodeSet(powerLevel: 0, testFrameCount: 30, testNodeid: 1).format(), hubitat.device.Protocol.ZWAVE))
}

/*
  Requests results from device of test initiated by deviceCommTest()
  Note: Attempts to retreive results at 10 second intervals, up to 3 times before timing out.
*/
def commTestResults() {
  logger("trace", "commTestResults()")

  sendHubCommand(new hubitat.device.HubAction(zwave.powerlevelV1.powerlevelTestNodeGet().format(), hubitat.device.Protocol.ZWAVE))
  state.deviceCommTests = state.deviceCommTests + 1
  logger("debug", "Asking device for results (${state.deviceCommTests}/3)")

  if (state.deviceCommTests <= 3){
    runIn(6, commTestResults)
  } else {
    unschedule(commTestResults)
    logger("warn", "Device unreachable")

    if (device.currentValue('status') != 'offline') {
      sendEvent([ name: "status", value: 'offline', descriptionText: "${device.displayName} is offline", isStateChange: true, displayed: true])
    }
  }
}

/*
  POWER LEVEL TEST REPORT RESPONSE

  Status Codes:
  STATUS_OF_OPERATION_ZW_TEST_FAILED	= 0
  STATUS_OF_OPERATION_ZW_TEST_SUCCES	= 1
  STATUS_OF_OPERATION_ZW_TEST_INPROGRESS	= 2
*/
def zwaveEvent(hubitat.zwave.commands.powerlevelv1.PowerlevelTestNodeReport cmd) {
  logger("trace", "zwaveEvent(PowerlevelTestNodeReport) - cmd: ${cmd.inspect()}")

  switch (cmd.statusOfOperation) {
    case 0:
      logger("warn", "FAILED - Received ${cmd.testFrameCount} out of 30 frames")
      unschedule(commTestResults)
      break;
    case 1:
      logger("info", "SUCCESS - Received ${cmd.testFrameCount} out of 30 frames")
      unschedule(commTestResults)
      break;
    case 2:
      logger("info", "IN PROGRESS - Received ${cmd.testFrameCount} out of 30 frames")
      break;
  }

  if (state.deviceCommTests > 3) {
    logger("warn", "TIMEOUT - Received ${cmd.testFrameCount} out of 30 frames")
    unschedule(commTestResults)
  }
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
  logger("trace", "zwaveEvent(ConfigurationReport) - cmd: ${cmd.inspect()}")
}

def zwaveEvent(hubitat.zwave.commands.deviceresetlocallyv1.DeviceResetLocallyNotification cmd) {
  logger("trace", "zwaveEvent(DeviceResetLocallyNotification) - cmd: ${cmd.inspect()}")
  logger("warn", "zwaveEvent(DeviceResetLocallyNotification) - device has reset itself")
}

def zwaveEvent(hubitat.zwave.commands.associationgrpinfov1.AssociationGroupNameReport cmd) {
  logger("trace", "zwaveEvent(AssociationGroupNameReport) - cmd: ${cmd.inspect()}")
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
  logger("trace", "zwaveEvent(AssociationReport) - cmd: ${cmd.inspect()}")
}

def zwaveEvent(hubitat.zwave.commands.powerlevelv1.PowerlevelReport cmd) {
  logger("trace", "zwaveEvent(PowerlevelReport) - cmd: ${cmd.inspect()}")

  def power = (cmd.powerLevel > 0) ? "minus${cmd.powerLevel}dBm" : "NormalPower"
  logger("info", "Powerlevel Report: Power: ${power}, Timeout: ${cmd.timeout}")
  deviceUpdate()
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
  logger("trace", "zwaveEvent(VersionReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['applicationVersion'] = "${cmd.applicationVersion}"
  state.deviceInfo['applicationSubVersion'] = "${cmd.applicationSubVersion}"
  state.deviceInfo['zWaveLibraryType'] = "${cmd.zWaveLibraryType}"
  state.deviceInfo['zWaveProtocolVersion'] = "${cmd.zWaveProtocolVersion}"
  state.deviceInfo['zWaveProtocolSubVersion'] = "${cmd.zWaveProtocolSubVersion}"

  updateDataValue("firmware", "${cmd.applicationVersion}.${cmd.applicationSubVersion}")
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
  logger("trace", "zwaveEvent(DeviceSpecificReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['deviceIdData'] = "${cmd.deviceIdData}"
  state.deviceInfo['deviceIdDataFormat'] = "${cmd.deviceIdDataFormat}"
  state.deviceInfo['deviceIdDataLengthIndicator'] = "l${cmd.deviceIdDataLengthIndicator}"
  state.deviceInfo['deviceIdType'] = "${cmd.deviceIdType}"
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
  logger("trace", "zwaveEvent(ManufacturerSpecificReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['manufacturerId'] = "${cmd.manufacturerId}"
  state.deviceInfo['manufacturerName'] = "${cmd.manufacturerName}"
  state.deviceInfo['productId'] = "${cmd.productId}"
  state.deviceInfo['productTypeId'] = "${cmd.productTypeId}"

  String msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
  updateDataValue("MSR", msr)
  updateDataValue("manufacturer", cmd.manufacturerName)

  createEvent(descriptionText: "$device.displayName MSR: $msr", isStateChange: false)
}

def zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv2.FirmwareMdReport cmd) {
  logger("trace", "zwaveEvent(FirmwareMdReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['firmwareChecksum'] = "${cmd.checksum}"
  state.deviceInfo['firmwareId'] = "${cmd.firmwareId}"
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
  logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - cmd: ${cmd.inspect()}")

  setSecured()
  def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)

  } else {
    logger("warn", "zwaveEvent(SecurityMessageEncapsulation) - Unable to extract Secure command from: ${cmd.inspect()}")
  }
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
  logger("trace", "zwaveEvent(Crc16Encap) - cmd: ${cmd.inspect()}")

  def encapsulatedCommand = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(Crc16Encap) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "zwaveEvent(Crc16Encap) - Unable to extract CRC16 command from: ${cmd.inspect()}")
  }
}

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
  logger("trace", "zwaveEvent(MultiChannelCmdEncap) - cmd: ${cmd.inspect()}")

  def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(MultiChannelCmdEncap) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
  } else {
    logger("warn", "zwaveEvent(MultiChannelCmdEncap) - Unable to extract MultiChannel command from: ${cmd.inspect()}")
  }
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityCommandsSupportedReport cmd) {
  logger("trace", "zwaveEvent(SecurityCommandsSupportedReport) - cmd: ${cmd.inspect()}")
  setSecured()
}

def zwaveEvent(hubitat.zwave.commands.securityv1.NetworkKeyVerify cmd) {
  logger("trace", "zwaveEvent(NetworkKeyVerify) - cmd: ${cmd.inspect()}")
  logger("info", "Secure inclusion was successful")
  setSecured()
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  logger("warn", "zwaveEvent(Command) - Unhandled - cmd: ${cmd.inspect()}")
  [:]
}

private secure(hubitat.zwave.Command cmd) {
  logger("trace", "secure(Command) - cmd: ${cmd.inspect()} isSecured(): ${isSecured()}")

  if (isSecured()) {
    zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
  } else {
    cmd.format()
  }
}

private secureSequence(Collection commands, Integer delayBetweenArgs=250) {
  logger("trace", "secureSequence(Command) - commands: ${commands.inspect()} delayBetweenArgs: ${delayBetweenArgs}")
  delayBetween(commands.collect{ secure(it) }, delayBetweenArgs)
}

private setSecured() {
  updateDataValue("secured", "true")
}
private isSecured() {
  getDataValue("secured") == "true"
}

/**
 * @param level Level to log at, see LOG_LEVELS for options
 * @param msg Message to log
 */
private logger(level, msg) {
  if (level && msg) {
    Integer levelIdx = LOG_LEVELS.indexOf(level)
    Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
    if (setLevelIdx < 0) {
      setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
    }
    if (levelIdx <= setLevelIdx) {
      log."${level}" "${msg}"
    }
  }
}