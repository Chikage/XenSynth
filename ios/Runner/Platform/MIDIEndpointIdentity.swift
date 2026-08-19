import CoreMIDI

enum MIDIEndpointIdentity {
  static func targetId(for endpoint: MIDIEndpointRef) -> String {
    var entity = MIDIEntityRef()
    if MIDIEndpointGetEntity(endpoint, &entity) == noErr, entity != 0 {
      var device = MIDIDeviceRef()
      if MIDIEntityGetDevice(entity, &device) == noErr, device != 0,
         let id = uniqueId(for: device) {
        return "coremidi-device:\(id)"
      }
      if let id = uniqueId(for: entity) {
        return "coremidi-entity:\(id)"
      }
    }
    return "coremidi-endpoint:\(uniqueId(for: endpoint) ?? Int32(bitPattern: endpoint))"
  }

  private static func uniqueId(for object: MIDIObjectRef) -> Int32? {
    var id: Int32 = 0
    guard MIDIObjectGetIntegerProperty(object, kMIDIPropertyUniqueID, &id) == noErr,
          id != 0 else {
      return nil
    }
    return id
  }
}
