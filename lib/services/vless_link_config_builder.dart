import 'dart:convert';

import '../models/vless_config_model.dart';
import 'xray_config_normalizer.dart';

/// Builds a full Xray JSON config from a `vless://` link, replacing the
/// config generator inside `flutter_v2ray_plus` for TCP-based servers.
///
/// ## Why the plugin's own builder is not used
///
/// `FlutterV2RayURL.getFullConfiguration()` produces a config that diverges
/// from what working clients (Happ, v2rayNG) generate for the same link, and
/// several of those divergences are exactly the pieces a VLESS + REALITY +
/// `xtls-rprx-vision` server needs:
///
/// * **sniffing is disabled** on the SOCKS inbound (`enabled: false` with the
///   `destOverride` list stripped by its null-scrubber), so domain routing and
///   destination-override never work;
/// * **`routing.domainStrategy` is `"UseIp"`** — not a legal routing value
///   (`AsIs` / `IPIfNonMatch` / `IPOnDemand`), so Xray silently falls back;
/// * the VLESS user carries a junk `"security": "auto"` field and the REALITY
///   block a meaningless `allowInsecure: true`;
/// * DNS is pinned to 8.8.8.8 with no `queryStrategy`.
///
/// Meanwhile the *panel's* JSON for the same server — the exact config the
/// user confirmed working in Happ — ships sniffing on, `IPIfNonMatch`
/// routing, clean user/REALITY objects and 1.1.1.1 DNS. This builder emits
/// that proven shape from the parsed link fields, so a server behaves
/// identically no matter whether the subscription delivered it as JSON or as
/// a `vless://` line.
///
/// Only `network == tcp` links are handled here (REALITY/Vision servers are
/// always raw TCP). Anything else — ws, grpc, xhttp — returns null and the
/// caller falls back to the plugin's builder, which carries the transport
/// tables for those.
class VlessLinkConfigBuilder {
  VlessLinkConfigBuilder._();

  /// Returns the full Xray JSON for [config], or null when this builder does
  /// not cover the server (non-VLESS protocol or non-TCP transport).
  ///
  /// The result is already normalized (local ports, hygiene) via
  /// [XrayConfigNormalizer], so callers can hand it straight to the engine.
  static String? build(VlessConfig config) {
    if (config.protocol != 'vless') return null;

    final network = config.type.trim().toLowerCase();
    if (network.isNotEmpty && network != 'tcp') return null;

    if (config.address.isEmpty || config.uuid.isEmpty) return null;

    final security = config.security.trim().toLowerCase();

    final user = <String, dynamic>{
      'id': config.uuid,
      'encryption': 'none',
      if (config.flow.isNotEmpty) 'flow': config.flow,
    };

    final streamSettings = <String, dynamic>{
      'network': 'tcp',
      'tcpSettings': <String, dynamic>{},
    };

    if (security == 'reality') {
      streamSettings['security'] = 'reality';
      streamSettings['realitySettings'] = <String, dynamic>{
        // REALITY cannot run without a serverName to imitate; a link that
        // omits sni can only mean "imitate the address itself".
        'serverName': config.sni.isNotEmpty ? config.sni : config.address,
        'publicKey': config.pbk,
        if (config.sid.isNotEmpty) 'shortId': config.sid,
        'fingerprint': config.fp.isNotEmpty ? config.fp : 'chrome',
        if (config.spx.isNotEmpty) 'spiderX': config.spx,
      };
    } else if (security == 'tls') {
      streamSettings['security'] = 'tls';
      streamSettings['tlsSettings'] = <String, dynamic>{
        if (config.sni.isNotEmpty) 'serverName': config.sni,
        if (config.fp.isNotEmpty) 'fingerprint': config.fp,
        'allowInsecure': false,
      };
    }

    final xrayConfig = <String, dynamic>{
      'log': <String, dynamic>{'loglevel': 'warning'},
      'dns': <String, dynamic>{
        'servers': <String>['1.1.1.1', '1.0.0.1'],
        'queryStrategy': 'UseIP',
      },
      'inbounds': <dynamic>[
        <String, dynamic>{
          'tag': 'socks',
          'listen': XrayLocalPorts.listen,
          'port': XrayLocalPorts.socks,
          'protocol': 'socks',
          'settings': <String, dynamic>{'auth': 'noauth', 'udp': true},
          'sniffing': <String, dynamic>{
            'enabled': true,
            'destOverride': <String>['http', 'tls', 'quic'],
            'routeOnly': false,
          },
        },
      ],
      'outbounds': <dynamic>[
        <String, dynamic>{
          'tag': 'proxy',
          'protocol': 'vless',
          'settings': <String, dynamic>{
            'vnext': <dynamic>[
              <String, dynamic>{
                'address': config.address,
                'port': config.port,
                'users': <dynamic>[user],
              },
            ],
          },
          'streamSettings': streamSettings,
        },
        <String, dynamic>{'tag': 'direct', 'protocol': 'freedom'},
        <String, dynamic>{'tag': 'block', 'protocol': 'blackhole'},
      ],
      'routing': <String, dynamic>{
        'domainMatcher': 'hybrid',
        'domainStrategy': 'IPIfNonMatch',
        'rules': <dynamic>[
          <String, dynamic>{
            'type': 'field',
            'protocol': <String>['bittorrent'],
            'outboundTag': 'direct',
          },
        ],
      },
    };

    // The normalizer is a no-op for a well-formed config, but running it keeps
    // a single choke point for port policy no matter which path built the JSON.
    return XrayConfigNormalizer.normalizeJsonString(jsonEncode(xrayConfig));
  }
}
