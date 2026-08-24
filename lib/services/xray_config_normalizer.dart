import 'dart:convert';

/// Local proxy ports that the `flutter_v2ray_plus` Android layer hard-codes.
///
/// These are plain field defaults on `XrayConfig.kt` and
/// `FlutterV2rayPlugin.buildXrayConfig()` never overrides them, so there is no
/// way to change them from Dart. Every config handed to the plugin has to line
/// up with these numbers or the tunnel silently dies.
class XrayLocalPorts {
  XrayLocalPorts._();

  /// SOCKS5 inbound. `tun2socks` is spawned with
  /// `-proxy socks5://127.0.0.1:10807`, so this port is the one and only place
  /// the TUN device forwards packets to.
  static const int socks = 10807;

  /// HTTP inbound.
  static const int http = 10808;

  /// Xray gRPC API inbound (`dokodemo-door`, tag `api`). The plugin appends it
  /// unconditionally, so nothing else may occupy this port.
  static const int api = 10809;

  static const String listen = '127.0.0.1';

  /// Ports the plugin owns. Any inbound from a subscription that lands on one
  /// of them has to be moved or dropped.
  static const Set<int> reserved = <int>{socks, http, api};
}

/// Makes a raw Xray JSON config (as produced by a panel such as Remnawave,
/// Marzban or 3x-ui, or exported from a client like Happ) actually usable by
/// `flutter_v2ray_plus`.
///
/// ## Why this exists
///
/// `XrayCoreManager.injectInbounds()` only injects its own SOCKS/HTTP inbounds
/// when the supplied config does **not** already contain an inbound with that
/// protocol, and it always appends its API inbound on
/// [XrayLocalPorts.api]. Panel-generated configs practically always ship
/// `socks` on 10808 and `http` on 10809, which breaks the tunnel twice over:
///
/// 1. A `socks` inbound already exists, so the plugin skips injecting one on
///    [XrayLocalPorts.socks]. `tun2socks` then dials 127.0.0.1:10807 where
///    nobody is listening — the UI happily reports CONNECTED while not a single
///    packet is proxied.
/// 2. The config's `http` inbound sits on 10809, exactly where the plugin
///    injects its `dokodemo-door` API inbound. Xray cannot bind the port twice
///    and the core dies during startup.
///
/// That is why such a server works in Happ (which builds its own inbounds) but
/// not here, and why plain `vless://` links work — the plugin's own URL builder
/// already emits the SOCKS inbound on 10807.
///
/// ## What it does
///
/// * Re-homes the local `socks`/`http` inbounds onto the ports the native layer
///   actually wires up, preserving the panel's `sniffing` and `settings`.
/// * Drops anything that would collide with the injected API inbound.
/// * Strips the `api` / `stats` / `policy` blocks and the API routing rule that
///   the plugin overwrites anyway, which keeps the transform idempotent.
/// * Removes non-Xray metadata keys (`remarks`, `meta`, …) that panels attach.
/// * Repairs outbounds that Xray would reject outright: a VLESS user without
///   `encryption`, a REALITY block without a `fingerprint`, or an
///   `xtls-rprx-vision` flow on a transport that cannot carry it.
///
/// Outbound routing, DNS and transport security are otherwise left untouched —
/// the point is to fix the plumbing, never to second-guess the server.
class XrayConfigNormalizer {
  XrayConfigNormalizer._();

  /// Top-level keys panels add that are not part of the Xray schema.
  static const Set<String> _metadataKeys = <String>{
    'remarks',
    'meta',
    'name',
    'comment',
    '_comment',
    'subscription',
    'profile',
    'group',
  };

  /// Blocks the plugin regenerates itself, so keeping them only invites drift.
  static const Set<String> _pluginOwnedKeys = <String>{
    'api',
    'stats',
    'policy',
  };

  /// Transports that cannot carry the XTLS Vision flow. Leaving `flow` set on
  /// one of these makes Xray refuse to start.
  static const Set<String> _flowIncompatibleNetworks = <String>{
    'ws',
    'websocket',
    'h2',
    'http',
    'httpupgrade',
    'xhttp',
    'splithttp',
    'grpc',
    'gun',
    'quic',
    'kcp',
    'mkcp',
  };

  /// Normalize a JSON string. Returns the input untouched if it cannot be
  /// parsed — a broken config should fail loudly in the engine, not here.
  static String normalizeJsonString(String rawJson) {
    if (rawJson.isEmpty) return rawJson;
    try {
      final decoded = jsonDecode(rawJson);
      if (decoded is! Map) return rawJson;
      final normalized = normalize(Map<String, dynamic>.from(decoded));
      return jsonEncode(normalized);
    } catch (_) {
      return rawJson;
    }
  }

  /// Normalize a decoded config map. The input is never mutated.
  static Map<String, dynamic> normalize(Map<String, dynamic> source) {
    final config = _deepCopy(source);

    for (final key in _metadataKeys) {
      config.remove(key);
    }
    for (final key in _pluginOwnedKeys) {
      config.remove(key);
    }

    config['inbounds'] = _normalizeInbounds(config['inbounds']);

    final outbounds = _normalizeOutbounds(config['outbounds']);
    if (outbounds != null) config['outbounds'] = outbounds;

    final routing = _normalizeRouting(config['routing']);
    if (routing == null) {
      config.remove('routing');
    } else {
      config['routing'] = routing;
    }

    _ensureLocalDnsInterception(config);

    return config;
  }

  // ---------------------------------------------------------------------------
  // DNS interception (the v2rayNG pattern)
  // ---------------------------------------------------------------------------

  /// Makes DNS deterministic inside the tunnel.
  ///
  /// On Android the VPN layer advertises a public resolver (8.8.8.8 by
  /// default), so every app's DNS query enters the tunnel as a raw UDP:53
  /// packet: tun → tun2socks → the SOCKS inbound → routing → the proxy
  /// outbound. Over a VLESS + `xtls-rprx-vision` link that UDP leg rides on
  /// XUDP — one more moving part that panel configs never exercise, because
  /// clients like Happ resolve DNS in their own stack and never push raw
  /// UDP:53 through Xray. When that leg misbehaves, the tunnel looks
  /// CONNECTED while every name lookup dies — "no internet" with a green
  /// status icon.
  ///
  /// The fix is the exact shape v2rayNG generates for its VPN mode:
  ///
  /// * a `dns-out` outbound (`protocol: dns`) that intercepts DNS queries —
  ///   A/AAAA are answered by Xray's built-in DNS module;
  /// * a routing rule sending `port 53` traffic from the local SOCKS inbound
  ///   to `dns-out`, placed before every other rule (scoped by `inboundTag`
  ///   so the DNS module's own upstream queries can never loop back into it);
  /// * a `tcp://` resolver prepended to `dns.servers`, so the module's
  ///   lookups travel through the proxy as plain TCP:53 — the one transport a
  ///   Vision link is guaranteed to carry;
  /// * a `udp/443` block rule (when a blackhole outbound exists): Vision
  ///   rejects QUIC anyway, so browsers should fail over to TCP instantly
  ///   instead of waiting out a timeout.
  ///
  /// Only applies to configs that actually have a local SOCKS inbound, and is
  /// idempotent — re-normalizing never stacks a second rule or outbound.
  static void _ensureLocalDnsInterception(Map<String, dynamic> config) {
    final inbounds = config['inbounds'];
    if (inbounds is! List) return;

    String? socksTag;
    for (final inbound in inbounds) {
      if (inbound is Map && inbound['protocol'] == 'socks') {
        socksTag = (inbound['tag'] as Object?)?.toString();
        break;
      }
    }
    if (socksTag == null || socksTag.isEmpty) return;

    final outbounds = config['outbounds'];
    if (outbounds is! List || outbounds.isEmpty) return;

    // Reuse an existing dns outbound; add the canonical one otherwise.
    String dnsTag = '';
    String blackholeTag = '';
    for (final outbound in outbounds) {
      if (outbound is! Map) continue;
      final protocol = (outbound['protocol'] as Object?)?.toString() ?? '';
      final tag = (outbound['tag'] as Object?)?.toString() ?? '';
      if (protocol == 'dns' && dnsTag.isEmpty) {
        dnsTag = tag.isEmpty ? 'dns-out' : tag;
        if (tag.isEmpty) outbound['tag'] = dnsTag;
      } else if (protocol == 'blackhole' && blackholeTag.isEmpty) {
        blackholeTag = tag;
      }
    }
    if (dnsTag.isEmpty) {
      dnsTag = 'dns-out';
      outbounds.add(<String, dynamic>{'protocol': 'dns', 'tag': dnsTag});
    }

    // DNS module servers: guarantee a TCP resolver comes first.
    final dns = config['dns'] is Map
        ? Map<String, dynamic>.from(config['dns'] as Map)
        : <String, dynamic>{};
    final servers = dns['servers'] is List
        ? List<dynamic>.from(dns['servers'] as List)
        : <dynamic>[];
    final hasStreamResolver = servers.any((s) =>
        s is String && (s.startsWith('tcp://') || s.startsWith('https://')));
    if (!hasStreamResolver) {
      servers.insertAll(0, <String>['tcp://1.1.1.1', 'tcp://1.0.0.1']);
    }
    dns['servers'] = servers;
    dns.putIfAbsent('queryStrategy', () => 'UseIP');
    config['dns'] = dns;

    // Routing rules, prepended so nothing shadows them.
    final routing = config['routing'] is Map
        ? Map<String, dynamic>.from(config['routing'] as Map)
        : <String, dynamic>{};
    final rules = routing['rules'] is List
        ? List<dynamic>.from(routing['rules'] as List)
        : <dynamic>[];

    final hasDnsRule = rules.any((r) => r is Map && r['outboundTag'] == dnsTag);
    final hasQuicBlock = rules.any((r) =>
        r is Map &&
        r['outboundTag'] == blackholeTag &&
        r['port'] == '443' &&
        r['network'] == 'udp');

    final prepend = <dynamic>[
      if (!hasDnsRule)
        <String, dynamic>{
          'type': 'field',
          'inboundTag': <String>[socksTag],
          'port': '53',
          'outboundTag': dnsTag,
        },
      if (blackholeTag.isNotEmpty && !hasQuicBlock)
        <String, dynamic>{
          'type': 'field',
          'inboundTag': <String>[socksTag],
          'port': '443',
          'network': 'udp',
          'outboundTag': blackholeTag,
        },
    ];
    if (prepend.isNotEmpty) {
      routing['rules'] = <dynamic>[...prepend, ...rules];
    } else {
      routing['rules'] = rules;
    }
    config['routing'] = routing;
  }

  // ---------------------------------------------------------------------------
  // Inbounds
  // ---------------------------------------------------------------------------

  /// Rebuilds the inbound list so the local proxies sit on the ports the native
  /// layer forwards to.
  ///
  /// The first `socks` inbound is re-homed to [XrayLocalPorts.socks] and the
  /// first `http` inbound to [XrayLocalPorts.http]; their tags are normalized to
  /// `socks` / `http` so existing routing rules keep matching. Extra copies,
  /// every `dokodemo-door`, and anything else sitting on a reserved port are
  /// dropped. When no `socks` inbound survives, the list is simply left without
  /// one and the plugin injects a correct inbound itself.
  static List<dynamic> _normalizeInbounds(dynamic raw) {
    if (raw is! List) return <dynamic>[];

    Map<String, dynamic>? socksInbound;
    Map<String, dynamic>? httpInbound;
    final passthrough = <Map<String, dynamic>>[];

    for (final entry in raw) {
      if (entry is! Map) continue;
      final inbound = Map<String, dynamic>.from(entry);
      final protocol = (inbound['protocol'] as Object?)
              ?.toString()
              .trim()
              .toLowerCase() ??
          '';

      switch (protocol) {
        case 'socks':
          // Keep only the first one; a second would fight for the same port.
          socksInbound ??= _rebindLocalInbound(
            inbound,
            port: XrayLocalPorts.socks,
            tag: 'socks',
            forceUdp: true,
          );
          break;

        case 'http':
          httpInbound ??= _rebindLocalInbound(
            inbound,
            port: XrayLocalPorts.http,
            tag: 'http',
            forceUdp: false,
          );
          break;

        case 'dokodemo-door':
          // The plugin injects its own API inbound; a second one would clash.
          break;

        default:
          // Exotic inbound from the panel: keep it, unless it squats on a port
          // the native layer needs.
          final port = _asInt(inbound['port']);
          if (port != null && XrayLocalPorts.reserved.contains(port)) break;
          passthrough.add(inbound);
      }
    }

    return <dynamic>[
      if (socksInbound != null) socksInbound,
      if (httpInbound != null) httpInbound,
      ...passthrough,
    ];
  }

  /// Moves a local inbound onto [port] while preserving the panel's intent
  /// (sniffing rules, auth mode, UDP support).
  static Map<String, dynamic> _rebindLocalInbound(
    Map<String, dynamic> inbound, {
    required int port,
    required String tag,
    required bool forceUdp,
  }) {
    final result = Map<String, dynamic>.from(inbound);
    result['port'] = port;
    result['listen'] = XrayLocalPorts.listen;
    result['tag'] = tag;

    final settings = result['settings'] is Map
        ? Map<String, dynamic>.from(result['settings'] as Map)
        : <String, dynamic>{};

    if (forceUdp) {
      // tun2socks relies on SOCKS5 UDP ASSOCIATE for DNS and QUIC. A panel that
      // leaves it off would silently break both.
      settings['udp'] = true;
      final auth = (settings['auth'] as Object?)?.toString();
      if (auth == null || auth.isEmpty) settings['auth'] = 'noauth';
    }
    result['settings'] = settings;

    if (result['sniffing'] is! Map) {
      // Without sniffing, domain-based routing rules can never match, because
      // tun2socks only ever hands over IP addresses.
      result['sniffing'] = <String, dynamic>{
        'enabled': true,
        'destOverride': <String>['http', 'tls', 'quic'],
        'routeOnly': false,
      };
    }

    return result;
  }

  // ---------------------------------------------------------------------------
  // Outbounds
  // ---------------------------------------------------------------------------

  /// Repairs the outbound fields Xray validates strictly, leaving everything
  /// else exactly as the panel sent it.
  static List<dynamic>? _normalizeOutbounds(dynamic raw) {
    if (raw is! List) return null;

    return raw.map<dynamic>((entry) {
      if (entry is! Map) return entry;
      final outbound = Map<String, dynamic>.from(entry);
      final protocol =
          (outbound['protocol'] as Object?)?.toString().toLowerCase() ?? '';
      if (protocol != 'vless' && protocol != 'vmess') return outbound;

      final network = _networkOf(outbound);
      final security = _securityOf(outbound);

      final settings = outbound['settings'];
      if (settings is Map) {
        final settingsCopy = Map<String, dynamic>.from(settings);
        final vnext = settingsCopy['vnext'];
        if (vnext is List) {
          settingsCopy['vnext'] = vnext.map<dynamic>((server) {
            if (server is! Map) return server;
            final serverCopy = Map<String, dynamic>.from(server);

            final port = _asInt(serverCopy['port']);
            if (port != null) serverCopy['port'] = port;

            final users = serverCopy['users'];
            if (users is List) {
              serverCopy['users'] = users.map<dynamic>((user) {
                if (user is! Map) return user;
                return _normalizeUser(
                  Map<String, dynamic>.from(user),
                  protocol: protocol,
                  network: network,
                  security: security,
                );
              }).toList();
            }
            return serverCopy;
          }).toList();
        }
        outbound['settings'] = settingsCopy;
      }

      final stream = _normalizeStreamSettings(outbound['streamSettings']);
      if (stream != null) outbound['streamSettings'] = stream;

      return outbound;
    }).toList();
  }

  static Map<String, dynamic> _normalizeUser(
    Map<String, dynamic> user, {
    required String protocol,
    required String network,
    required String security,
  }) {
    if (protocol == 'vless') {
      // Xray rejects a VLESS user whose encryption field is missing or blank.
      final encryption = (user['encryption'] as Object?)?.toString() ?? '';
      if (encryption.isEmpty) user['encryption'] = 'none';
    }

    final flow = (user['flow'] as Object?)?.toString().trim() ?? '';
    if (flow.isEmpty) {
      user.remove('flow');
      return user;
    }

    // XTLS flows only exist over raw TCP with TLS/REALITY. Anywhere else Xray
    // refuses to start, so a panel that emitted the combination by mistake gets
    // the flow dropped rather than a dead tunnel.
    final incompatibleTransport = _flowIncompatibleNetworks.contains(network);
    final incompatibleSecurity = security != 'tls' && security != 'reality';
    if (incompatibleTransport || incompatibleSecurity) {
      user.remove('flow');
    } else {
      user['flow'] = flow;
    }

    return user;
  }

  static Map<String, dynamic>? _normalizeStreamSettings(dynamic raw) {
    if (raw is! Map) return null;
    final stream = Map<String, dynamic>.from(raw);
    final security = (stream['security'] as Object?)?.toString().toLowerCase();

    if (security == 'reality' && stream['realitySettings'] is Map) {
      final reality =
          Map<String, dynamic>.from(stream['realitySettings'] as Map);

      // REALITY needs a uTLS fingerprint to imitate; an empty one aborts the
      // handshake. Chrome is the safe, universally accepted default.
      final fingerprint = (reality['fingerprint'] as Object?)?.toString() ?? '';
      if (fingerprint.isEmpty) reality['fingerprint'] = 'chrome';

      stream['realitySettings'] = reality;
    }

    return stream;
  }

  // ---------------------------------------------------------------------------
  // Routing
  // ---------------------------------------------------------------------------

  /// Drops a previously injected API routing rule so re-normalizing a config
  /// cannot pile up duplicates.
  static Map<String, dynamic>? _normalizeRouting(dynamic raw) {
    if (raw is! Map) return null;
    final routing = Map<String, dynamic>.from(raw);
    final rules = routing['rules'];
    if (rules is! List) return routing;

    routing['rules'] = rules.where((rule) {
      if (rule is! Map) return true;
      if (rule['outboundTag'] != 'api') return true;
      final inboundTag = rule['inboundTag'];
      if (inboundTag is List) {
        return !inboundTag.any((tag) => tag == 'api');
      }
      return inboundTag != 'api';
    }).toList();

    return routing;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  static String _networkOf(Map<String, dynamic> outbound) {
    final stream = outbound['streamSettings'];
    if (stream is! Map) return 'tcp';
    final network = (stream['network'] as Object?)?.toString().toLowerCase();
    return (network == null || network.isEmpty) ? 'tcp' : network;
  }

  static String _securityOf(Map<String, dynamic> outbound) {
    final stream = outbound['streamSettings'];
    if (stream is! Map) return 'none';
    final security = (stream['security'] as Object?)?.toString().toLowerCase();
    return (security == null || security.isEmpty) ? 'none' : security;
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value.trim());
    return null;
  }

  static Map<String, dynamic> _deepCopy(Map<String, dynamic> source) {
    final decoded = jsonDecode(jsonEncode(source));
    return Map<String, dynamic>.from(decoded as Map);
  }
}
