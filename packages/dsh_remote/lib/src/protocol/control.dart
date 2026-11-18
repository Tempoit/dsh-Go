// SPDX-FileCopyrightText: 2026 DSH Remote contributors
// SPDX-License-Identifier: MIT
// See THIRD_PARTY_NOTICES.md.
// Control-channel frames of ds-harness-remote protocol v1. Conformance:
// test/fixtures/protocol-v1.
library;

import 'dart:convert';

import '../util/bytes.dart';

const int protocolVersion = 1;

/// UTF-8 JSON limit for non-relay Control frames.
const int maxControlFrameBytes = 64 * 1024;

/// UTF-8 JSON limit for `relay` Control frames and Relay data frames.
const int maxRelayFrameBytes = 1024 * 1024;

/// Most entries in a capability list (`hello`, `hello.ack`,
/// `harness.transport.describe`).
const int maxRemoteCapabilityEntries = 128;

const int _maxProtocolListEntries = 16;

const List<String> controlFrameTypes = [
  'hello',
  'hello.ack',
  'connect.request',
  'connect.incoming',
  'connect.accepted',
  'connect.rejected',
  'secure.handshake',
  'relay',
  'signal.offer',
  'signal.answer',
  'signal.ice',
  'transport.selected',
  'ping',
  'pong',
  'error',
];

/// `lan | p2p | turn | relay`, in preference order.
const List<String> remoteTransports = ['lan', 'p2p', 'turn', 'relay'];

/// A Control frame failed validation; the receiver must fail closed.
class ProtocolError implements Exception {
  const ProtocolError(this.message, [this.code = 'INVALID_MESSAGE']);
  final String code;
  final String message;

  @override
  String toString() => 'ProtocolError($code): $message';
}

/// Negotiated per-socket frame limits (`hello.ack`); both may only shrink
/// the v1 defaults.
class ControlFrameLimits {
  const ControlFrameLimits({this.maxControlFrameBytes, this.maxRelayFrameBytes});

  final int? maxControlFrameBytes;
  final int? maxRelayFrameBytes;
}

class ControlFrame {
  ControlFrame(this.type, this.payload, {String? id, int? timestamp})
    : id = id ?? uuidV7(),
      timestamp = timestamp ?? DateTime.now().millisecondsSinceEpoch;

  final String id;
  final String type;
  final int timestamp;
  final JsonMap payload;

  JsonMap toJson() => {'v': protocolVersion, 'id': id, 'type': type, 'timestamp': timestamp, 'payload': payload};
}

/// Validates [input] like upstream `parseControlFrame`: strict envelope,
/// known type, typed payload. Unknown payload fields are tolerated (they are
/// forward-compatible additions), unknown envelope fields are not.
ControlFrame parseControlFrame(Object? input) {
  final map = asMap(input);
  if (map == null) throw const ProtocolError('Control frame must be a JSON object.');
  const allowed = {'v', 'id', 'type', 'timestamp', 'payload'};
  for (final key in map.keys) {
    if (!allowed.contains(key)) throw ProtocolError('Unexpected Control envelope field "$key".');
  }
  if (map['v'] != protocolVersion) {
    throw const ProtocolError('Unsupported Control protocol version.', 'UNSUPPORTED_VERSION');
  }
  final id = map['id'];
  if (id is! String || id.isEmpty) throw const ProtocolError('Control frame id is required.');
  final type = map['type'];
  if (type is! String || !controlFrameTypes.contains(type)) {
    throw const ProtocolError('Unknown Control frame type.');
  }
  final timestamp = map['timestamp'];
  if (timestamp is! int || timestamp <= 0) {
    throw const ProtocolError('Control frame timestamp must be a positive integer.');
  }
  if (!map.containsKey('payload')) throw const ProtocolError('Control frame payload is required.');
  final payload = asMap(map['payload']);
  if (payload == null) throw ProtocolError('$type payload must be an object.');
  _validatePayload(type, payload);
  return ControlFrame(type, payload, id: id, timestamp: timestamp);
}

/// Validates and encodes [frame], enforcing the (negotiated) byte limit.
String encodeControlFrame(ControlFrame frame, [ControlFrameLimits limits = const ControlFrameLimits()]) {
  final parsed = parseControlFrame(frame.toJson());
  final encoded = jsonEncode(parsed.toJson());
  _assertSize(utf8.encode(encoded).length, parsed.type, limits);
  return encoded;
}

/// Decodes one Control text frame, enforcing the (negotiated) byte limit.
ControlFrame decodeControlFrame(String data, [ControlFrameLimits limits = const ControlFrameLimits()]) {
  final bytes = utf8.encode(data).length;
  if (bytes > maxRelayFrameBytes) {
    throw const ProtocolError('Control frame exceeds the Relay frame limit.', 'FRAME_TOO_LARGE');
  }
  final Object? json;
  try {
    json = decodeWireJson(data);
  } on FormatException {
    throw const ProtocolError('Control frame is not JSON.');
  }
  final frame = parseControlFrame(json);
  _assertSize(bytes, frame.type, limits);
  return frame;
}

/// Validates and encodes a frame for the independent Relay data WebSocket.
/// Its envelope is the Control envelope with a free-form `type`; every
/// frame counts against the Relay limit.
String encodeRelayDataFrame(ControlFrame frame, [ControlFrameLimits limits = const ControlFrameLimits()]) {
  final parsed = _parseRelayDataFrame(frame.toJson());
  final encoded = jsonEncode(parsed.toJson());
  _assertSize(utf8.encode(encoded).length, 'relay', limits);
  return encoded;
}

/// Decodes one Relay data text frame (`relay.ready`, `relay`,
/// `relay.detach`, `ping`, ...), checking the size before parsing.
ControlFrame decodeRelayDataFrame(String data, [ControlFrameLimits limits = const ControlFrameLimits()]) {
  _assertSize(utf8.encode(data).length, 'relay', limits);
  final Object? json;
  try {
    json = decodeWireJson(data);
  } on FormatException {
    throw const ProtocolError('Invalid Relay data frame.');
  }
  return _parseRelayDataFrame(json);
}

ControlFrame _parseRelayDataFrame(Object? input) {
  final map = asMap(input);
  final id = map?['id'];
  final type = map?['type'];
  final timestamp = map?['timestamp'];
  final payload = asMap(map?['payload']);
  if (map == null ||
      map.length != 5 ||
      map['v'] != protocolVersion ||
      id is! String ||
      id.isEmpty ||
      type is! String ||
      type.isEmpty ||
      timestamp is! int ||
      timestamp <= 0 ||
      timestamp > maxSafeInteger ||
      payload == null) {
    throw const ProtocolError('Invalid Relay data frame.');
  }
  return ControlFrame(type, payload, id: id, timestamp: timestamp);
}

void _assertSize(int bytes, String type, ControlFrameLimits limits) {
  final relay = type == 'relay';
  final configured = relay ? limits.maxRelayFrameBytes : limits.maxControlFrameBytes;
  final maximum = relay ? maxRelayFrameBytes : maxControlFrameBytes;
  final limit = configured == null ? maximum : (configured < maximum ? configured : maximum);
  if (limit <= 0) throw const ProtocolError('Control frame limit is invalid.');
  if (bytes > limit) {
    throw ProtocolError('${relay ? 'Relay' : 'Control'} frame exceeds its limit.', 'FRAME_TOO_LARGE');
  }
}

// ---------------------------------------------------------------------------
// Negotiation

/// Highest version in both sets, or `null`.
int? selectProtocolVersion(List<int> offered, [List<int> supported = const [protocolVersion]]) {
  final common = offered.where(supported.contains).toList()..sort();
  return common.isEmpty ? null : common.last;
}

/// [supported] filtered to [offered], in [supported] order.
List<String> selectCapabilities(List<String> offered, List<String> supported) {
  final set = offered.toSet();
  return supported.toSet().where(set.contains).toList();
}

/// Validates the Server's `hello.ack.capabilities` against what was offered.
/// A legacy Server omitting the field implies `transport.relay`.
List<String> acceptNegotiatedCapabilities(List<String> offered, List<String>? negotiated) {
  final accepted = negotiated ?? const ['transport.relay'];
  if (selectCapabilities(accepted, offered).length != accepted.length) {
    throw const ProtocolError('Server selected a capability that the peer did not offer.');
  }
  return [...accepted];
}

// ---------------------------------------------------------------------------
// Payload schemas

void _validatePayload(String type, JsonMap p) {
  switch (type) {
    case 'hello':
      _enum(p, 'role', const ['host', 'client']);
      _nonEmpty(p, 'deviceId');
      _nonEmpty(p, 'accessToken');
      final protocols = _list(p, 'protocols');
      if (protocols.isEmpty ||
          protocols.length > _maxProtocolListEntries ||
          protocols.any((v) => v is! int || v < 0 || v > maxSafeInteger) ||
          protocols.toSet().length != protocols.length) {
        throw const ProtocolError('hello.protocols is invalid.');
      }
      _capabilities(p, required: true);
      _optionalString(p, 'clientVersion');
      _optionalString(p, 'harnessVersion');
    case 'hello.ack':
      if (p['protocol'] != protocolVersion) {
        throw const ProtocolError('hello.ack selected an unsupported protocol.', 'UNSUPPORTED_VERSION');
      }
      _nonEmpty(p, 'serverVersion');
      _nonEmpty(p, 'connectionSessionId');
      _positiveInt(p, 'heartbeatIntervalMs');
      _positiveInt(p, 'maxControlFrameBytes', max: maxControlFrameBytes);
      _positiveInt(p, 'maxRelayFrameBytes', max: maxRelayFrameBytes);
      _capabilities(p, required: false);
      if (p.containsKey('webrtcEnabled') && p['webrtcEnabled'] is! bool) {
        throw const ProtocolError('hello.ack.webrtcEnabled must be a boolean.');
      }
      if (p.containsKey('webrtcFallbackTimeoutMs')) _positiveInt(p, 'webrtcFallbackTimeoutMs');
    case 'connect.request':
      _nonEmpty(p, 'hostDeviceId');
      _transports(p);
    case 'connect.incoming':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'clientDeviceId');
      final key = p['clientIdentityKey'];
      if (key is! String || !identityKeyPattern.hasMatch(key)) {
        throw const ProtocolError('connect.incoming.clientIdentityKey is not a canonical X25519 key.');
      }
      if (p['authorization'] != 'account') {
        throw const ProtocolError('connect.incoming.authorization must be "account".');
      }
      _transports(p);
      _relayAssignment(p);
    case 'connect.accepted':
      _nonEmpty(p, 'connectionId');
      _relayAssignment(p);
    case 'connect.rejected':
      _nonEmpty(p, 'connectionId');
      _optionalString(p, 'code');
      _optionalString(p, 'message');
    case 'secure.handshake':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'targetDeviceId');
      if (p['step'] != 1 && p['step'] != 2) throw const ProtocolError('secure.handshake.step must be 1 or 2.');
      _base64Url(p, 'data');
    case 'relay':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'targetDeviceId');
      final counter = p['counter'];
      if (counter is! int || counter < 0 || counter > maxSafeInteger) {
        throw const ProtocolError('relay.counter must be a non-negative safe integer.');
      }
      _base64Url(p, 'ciphertext');
    case 'signal.offer' || 'signal.answer':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'targetDeviceId');
      _nonEmpty(p, 'sdp');
    case 'signal.ice':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'targetDeviceId');
      if (asMap(p['candidate']) == null) {
        throw const ProtocolError('signal.ice.candidate must be an object.');
      }
    case 'transport.selected':
      _nonEmpty(p, 'connectionId');
      _nonEmpty(p, 'targetDeviceId');
      _enum(p, 'transport', remoteTransports);
    case 'ping' || 'pong':
      _nonEmpty(p, 'nonce');
    case 'error':
      _nonEmpty(p, 'code');
      _nonEmpty(p, 'message');
      if (p.containsKey('retryable') && p['retryable'] is! bool) {
        throw const ProtocolError('error.retryable must be a boolean.');
      }
      if (p.containsKey('connectionId')) _nonEmpty(p, 'connectionId');
  }
}

void _nonEmpty(JsonMap p, String key) {
  final value = p[key];
  if (value is! String || value.isEmpty) throw ProtocolError('$key must be a non-empty string.');
}

void _optionalString(JsonMap p, String key) {
  if (p.containsKey(key) && p[key] is! String) throw ProtocolError('$key must be a string.');
}

void _enum(JsonMap p, String key, List<String> values) {
  if (!values.contains(p[key])) throw ProtocolError('$key is not one of $values.');
}

void _positiveInt(JsonMap p, String key, {int? max}) {
  final value = p[key];
  if (value is! int || value <= 0 || value > maxSafeInteger || (max != null && value > max)) {
    throw ProtocolError('$key must be a positive integer${max == null ? '' : ' ≤ $max'}.');
  }
}

List<Object?> _list(JsonMap p, String key) {
  final value = p[key];
  if (value is! List) throw ProtocolError('$key must be an array.');
  return value.cast<Object?>();
}

void _capabilities(JsonMap p, {required bool required}) {
  if (!p.containsKey('capabilities')) {
    if (required) throw const ProtocolError('capabilities is required.');
    return;
  }
  final values = _list(p, 'capabilities');
  if (values.length > maxRemoteCapabilityEntries ||
      values.any((v) => v is! String || v.isEmpty) ||
      values.toSet().length != values.length) {
    throw const ProtocolError('capabilities must be at most $maxRemoteCapabilityEntries unique non-empty strings.');
  }
}

void _transports(JsonMap p) {
  final values = _list(p, 'preferredTransports');
  if (values.isEmpty || values.length > remoteTransports.length || values.any((v) => !remoteTransports.contains(v))) {
    throw const ProtocolError('preferredTransports is invalid.');
  }
}

void _base64Url(JsonMap p, String key) {
  final value = p[key];
  if (value is! String || !isCanonicalBase64Url(value)) {
    throw ProtocolError('$key must be canonical unpadded base64url.');
  }
}

/// Optional independent Relay assignment (`transport.relay.attach`).
void _relayAssignment(JsonMap p) {
  if (p.containsKey('relayEndpoint')) {
    final endpoint = p['relayEndpoint'];
    if (endpoint is! String || !(Uri.tryParse(endpoint)?.hasScheme ?? false)) {
      throw const ProtocolError('relayEndpoint must be a URL.');
    }
  }
  if (p.containsKey('relayTicket')) _nonEmpty(p, 'relayTicket');
  if (p.containsKey('relayNodeId')) _nonEmpty(p, 'relayNodeId');
  if (p.containsKey('relayEpoch')) {
    final epoch = p['relayEpoch'];
    if (epoch is! int || epoch < 0) throw const ProtocolError('relayEpoch must be a non-negative integer.');
  }
}
