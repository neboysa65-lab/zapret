import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/services/vless_link_config_builder.dart';
import 'package:vpn_subscription_app/services/xray_config_normalizer.dart';

/// A REALITY + XTLS Vision server exactly as a panel hands it out: SOCKS on
/// 10808, HTTP on 10809. Both collide with what flutter_v2ray_plus expects.
const String _panelRealityConfig = '''
{
  "dns": { "queryStrategy": "UseIP", "servers": ["1.1.1.1", "1.0.0.1"] },
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true },
      "sniffing": {
        "destOverride": ["http", "tls", "quic"],
        "enabled": true,
        "routeOnly": false
      },
      "tag": "socks"
    },
    {
      "listen": "127.0.0.1",
      "port": 10809,
      "protocol": "http",
      "settings": { "allowTransparent": false },
      "tag": "http"
    }
  ],
  "meta": null,
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "194.93.63.245",
            "port": 443,
            "users": [
              {
                "encryption": "none",
                "flow": "xtls-rprx-vision",
                "id": "7c08d043-2185-4fdb-afa1-b57375576e0d"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "realitySettings": {
          "fingerprint": "firefox",
          "publicKey": "bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ",
          "serverName": "pl01.appgw.store",
          "shortId": "45ee70d18247e5"
        },
        "security": "reality",
        "tcpSettings": {}
      },
      "tag": "proxy"
    },
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ],
  "remarks": "PL01",
  "routing": {
    "domainMatcher": "hybrid",
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "outboundTag": "direct", "protocol": ["bittorrent"], "type": "field" }
    ]
  }
}
''';

Map<String, dynamic> _normalize(String raw) =>
    jsonDecode(XrayConfigNormalizer.normalizeJsonString(raw))
        as Map<String, dynamic>;

List<Map<String, dynamic>> _inbounds(Map<String, dynamic> config) =>
    (config['inbounds'] as List)
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();

Map<String, dynamic>? _inboundByProtocol(
  Map<String, dynamic> config,
  String protocol,
) {
  for (final inbound in _inbounds(config)) {
    if (inbound['protocol'] == protocol) return inbound;
  }
  return null;
}

Map<String, dynamic> _firstUser(Map<String, dynamic> config) {
  final outbound = (config['outbounds'] as List).first as Map;
  final vnext = ((outbound['settings'] as Map)['vnext'] as List).first as Map;
  return Map<String, dynamic>.from((vnext['users'] as List).first as Map);
}

void main() {
  group('XrayConfigNormalizer — local inbound ports', () {
    test('moves the SOCKS inbound onto the port tun2socks dials', () {
      // The whole bug in one assertion: the native layer starts tun2socks with
      // -proxy socks5://127.0.0.1:10807 and never overrides that number.
      final socks = _inboundByProtocol(
        _normalize(_panelRealityConfig),
        'socks',
      );
      expect(socks, isNotNull);
      expect(socks!['port'], XrayLocalPorts.socks);
      expect(socks['port'], 10807);
      expect(socks['listen'], '127.0.0.1');
      expect(socks['tag'], 'socks');
    });

    test('frees the API port by moving the HTTP inbound off 10809', () {
      final config = _normalize(_panelRealityConfig);
      final http = _inboundByProtocol(config, 'http');
      expect(http, isNotNull);
      expect(http!['port'], XrayLocalPorts.http);

      // Nothing may sit on the port where the plugin injects its
      // dokodemo-door API inbound, or Xray dies binding it twice.
      final ports = _inbounds(config).map((i) => i['port']).toList();
      expect(ports, isNot(contains(XrayLocalPorts.api)));
      expect(ports.toSet().length, ports.length, reason: 'ports must be unique');
    });

    test('keeps the panel sniffing settings while re-homing the inbound', () {
      final socks =
          _inboundByProtocol(_normalize(_panelRealityConfig), 'socks')!;
      final sniffing = Map<String, dynamic>.from(socks['sniffing'] as Map);
      expect(sniffing['enabled'], isTrue);
      expect(sniffing['destOverride'], containsAll(['http', 'tls', 'quic']));
    });

    test('forces SOCKS UDP on, since tun2socks needs UDP ASSOCIATE', () {
      final config = _normalize('''
        {
          "inbounds": [
            {"port": 10808, "protocol": "socks", "settings": {"auth": "noauth"}}
          ],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      final settings =
          Map<String, dynamic>.from(_inboundByProtocol(config, 'socks')!['settings'] as Map);
      expect(settings['udp'], isTrue);
    });

    test('adds sniffing when the panel omitted it entirely', () {
      final config = _normalize('''
        {
          "inbounds": [{"port": 10808, "protocol": "socks"}],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      final sniffing = Map<String, dynamic>.from(
          _inboundByProtocol(config, 'socks')!['sniffing'] as Map);
      expect(sniffing['enabled'], isTrue);
    });

    test('drops duplicate SOCKS inbounds that would fight for the port', () {
      final config = _normalize('''
        {
          "inbounds": [
            {"port": 10808, "protocol": "socks"},
            {"port": 10810, "protocol": "socks"}
          ],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      final socksCount =
          _inbounds(config).where((i) => i['protocol'] == 'socks').length;
      expect(socksCount, 1);
    });

    test('drops dokodemo-door inbounds so the injected API one is alone', () {
      final config = _normalize('''
        {
          "inbounds": [
            {"port": 10808, "protocol": "socks"},
            {"port": 10809, "protocol": "dokodemo-door", "tag": "api"}
          ],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      expect(_inboundByProtocol(config, 'dokodemo-door'), isNull);
    });

    test('leaves no inbounds when the config had none, letting the plugin inject',
        () {
      final config = _normalize('''
        {"outbounds": [{"protocol": "freedom", "tag": "direct"}]}
      ''');
      expect(config['inbounds'], isEmpty);
    });

    test('tolerates a port delivered as a string', () {
      final config = _normalize('''
        {
          "inbounds": [{"port": "10808", "protocol": "socks"}],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      expect(_inboundByProtocol(config, 'socks')!['port'], 10807);
    });
  });

  group('XrayConfigNormalizer — outbounds are preserved', () {
    test('REALITY and Vision flow survive untouched', () {
      final config = _normalize(_panelRealityConfig);
      final outbound = (config['outbounds'] as List).first as Map;
      final stream = Map<String, dynamic>.from(
          outbound['streamSettings'] as Map);
      final reality =
          Map<String, dynamic>.from(stream['realitySettings'] as Map);

      expect(stream['security'], 'reality');
      expect(stream['network'], 'tcp');
      expect(reality['publicKey'], 'bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ');
      expect(reality['shortId'], '45ee70d18247e5');
      expect(reality['serverName'], 'pl01.appgw.store');
      expect(reality['fingerprint'], 'firefox');
      expect(_firstUser(config)['flow'], 'xtls-rprx-vision');
      expect(_firstUser(config)['encryption'], 'none');
    });

    test('the panel outbounds and the bittorrent rule are kept', () {
      final config = _normalize(_panelRealityConfig);
      // proxy + direct + block from the panel, plus the injected dns-out.
      final outbounds = (config['outbounds'] as List)
          .map((o) => (o as Map)['tag'])
          .toList();
      expect(outbounds, ['proxy', 'direct', 'block', 'dns-out']);
      final rules = (config['routing'] as Map)['rules'] as List;
      expect(
        rules.any((r) =>
            (r as Map)['outboundTag'] == 'direct' &&
            (r['protocol'] as List).contains('bittorrent')),
        isTrue,
      );
    });

    test('supplies encryption=none when a panel forgot it', () {
      final config = _normalize('''
        {
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {"vnext": [
                {"address": "1.2.3.4", "port": 443, "users": [{"id": "u"}]}
              ]},
              "streamSettings": {"network": "tcp", "security": "reality",
                "realitySettings": {"publicKey": "k"}}
            }
          ]
        }
      ''');
      expect(_firstUser(config)['encryption'], 'none');
    });

    test('defaults the REALITY fingerprint rather than failing the handshake',
        () {
      final config = _normalize('''
        {
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {"vnext": [
                {"address": "1.2.3.4", "port": 443, "users": [{"id": "u"}]}
              ]},
              "streamSettings": {"network": "tcp", "security": "reality",
                "realitySettings": {"publicKey": "k"}}
            }
          ]
        }
      ''');
      final stream = Map<String, dynamic>.from(
          ((config['outbounds'] as List).first as Map)['streamSettings'] as Map);
      final reality =
          Map<String, dynamic>.from(stream['realitySettings'] as Map);
      expect(reality['fingerprint'], 'chrome');
    });

    test('strips a Vision flow that its transport cannot carry', () {
      // Xray refuses to start on this combination; dropping the flow keeps the
      // server usable instead of dead.
      final config = _normalize('''
        {
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {"vnext": [
                {"address": "1.2.3.4", "port": 443,
                 "users": [{"id": "u", "flow": "xtls-rprx-vision"}]}
              ]},
              "streamSettings": {"network": "ws", "security": "tls"}
            }
          ]
        }
      ''');
      expect(_firstUser(config).containsKey('flow'), isFalse);
    });

    test('keeps a Vision flow on raw TCP with REALITY', () {
      final config = _normalize(_panelRealityConfig);
      expect(_firstUser(config)['flow'], 'xtls-rprx-vision');
    });
  });

  group('XrayConfigNormalizer — hygiene', () {
    test('removes panel metadata that is not part of the Xray schema', () {
      final config = _normalize(_panelRealityConfig);
      expect(config.containsKey('remarks'), isFalse);
      expect(config.containsKey('meta'), isFalse);
      expect(config.containsKey('dns'), isTrue);
    });

    test('is idempotent', () {
      final once = XrayConfigNormalizer.normalizeJsonString(_panelRealityConfig);
      final twice = XrayConfigNormalizer.normalizeJsonString(once);
      expect(twice, once);
    });

    test('never mutates the caller map', () {
      final source =
          jsonDecode(_panelRealityConfig) as Map<String, dynamic>;
      XrayConfigNormalizer.normalize(source);
      final socks = (source['inbounds'] as List).first as Map;
      expect(socks['port'], 10808, reason: 'input must be left alone');
    });

    test('returns malformed input unchanged instead of throwing', () {
      expect(XrayConfigNormalizer.normalizeJsonString('not json'), 'not json');
      expect(XrayConfigNormalizer.normalizeJsonString('[]'), '[]');
      expect(XrayConfigNormalizer.normalizeJsonString(''), '');
    });
  });

  group('XrayConfigNormalizer — DNS interception (v2rayNG pattern)', () {
    test('injects dns-out and routes port 53 from the SOCKS inbound to it', () {
      // Raw UDP:53 from the tun used to ride XUDP over the Vision outbound —
      // when that leg dies, the tunnel says CONNECTED while every lookup
      // fails. DNS now terminates in Xray's own resolver instead.
      final config = _normalize(_panelRealityConfig);
      final tags =
          (config['outbounds'] as List).map((o) => (o as Map)['tag']).toList();
      expect(tags, contains('dns-out'));

      final rules = (config['routing'] as Map)['rules'] as List;
      final dnsRule = Map<String, dynamic>.from(rules.first as Map);
      expect(dnsRule['outboundTag'], 'dns-out');
      expect(dnsRule['port'], '53');
      expect(dnsRule['inboundTag'], ['socks']);
    });

    test('blocks QUIC (udp/443) via the config\'s own blackhole tag', () {
      final rules =
          ((_normalize(_panelRealityConfig)['routing'] as Map)['rules'] as List)
              .map((r) => Map<String, dynamic>.from(r as Map))
              .toList();
      final quicRule = rules.firstWhere(
        (r) => r['port'] == '443' && r['network'] == 'udp',
      );
      // The panel names its blackhole outbound "block" — the rule must follow
      // the config's naming, not assume one.
      expect(quicRule['outboundTag'], 'block');
      expect(quicRule['inboundTag'], ['socks']);
    });

    test('prepends TCP resolvers so lookups never depend on UDP over Vision',
        () {
      final dns =
          Map<String, dynamic>.from(_normalize(_panelRealityConfig)['dns'] as Map);
      final servers = dns['servers'] as List;
      expect(servers.first, 'tcp://1.1.1.1');
      expect(servers, containsAll(['1.1.1.1', '1.0.0.1']));
    });

    test('leaves dns servers alone when a stream resolver is already present',
        () {
      final config = _normalize('''
        {
          "dns": {"servers": ["https://1.1.1.1/dns-query"]},
          "inbounds": [{"port": 10808, "protocol": "socks", "tag": "socks"}],
          "outbounds": [{"protocol": "freedom", "tag": "direct"}]
        }
      ''');
      expect((config['dns'] as Map)['servers'], ['https://1.1.1.1/dns-query']);
    });

    test('reuses an existing dns outbound instead of adding a second one', () {
      final config = _normalize('''
        {
          "inbounds": [{"port": 10808, "protocol": "socks", "tag": "socks"}],
          "outbounds": [
            {"protocol": "freedom", "tag": "direct"},
            {"protocol": "dns", "tag": "dns_custom"}
          ]
        }
      ''');
      final dnsOutbounds = (config['outbounds'] as List)
          .where((o) => (o as Map)['protocol'] == 'dns')
          .toList();
      expect(dnsOutbounds.length, 1);
      final rules = (config['routing'] as Map)['rules'] as List;
      expect((rules.first as Map)['outboundTag'], 'dns_custom');
    });

    test('does not touch configs without a local SOCKS inbound', () {
      final config = _normalize('''
        {"outbounds": [{"protocol": "freedom", "tag": "direct"}]}
      ''');
      final tags =
          (config['outbounds'] as List).map((o) => (o as Map)['tag']).toList();
      expect(tags, isNot(contains('dns-out')));
      expect(config.containsKey('dns'), isFalse);
    });

    test('interception is idempotent — no rule or outbound stacking', () {
      final once = XrayConfigNormalizer.normalizeJsonString(_panelRealityConfig);
      final twice = XrayConfigNormalizer.normalizeJsonString(once);
      expect(twice, once);

      final config = jsonDecode(twice) as Map<String, dynamic>;
      final dnsOutbounds = (config['outbounds'] as List)
          .where((o) => (o as Map)['protocol'] == 'dns')
          .length;
      expect(dnsOutbounds, 1);
      final dnsRules = ((config['routing'] as Map)['rules'] as List)
          .where((r) => (r as Map)['outboundTag'] == 'dns-out')
          .length;
      expect(dnsRules, 1);
    });
  });

  group('VlessLinkConfigBuilder — vless:// links build the proven config', () {
    const realityUrl =
        'vless://7c08d043-2185-4fdb-afa1-b57375576e0d@194.93.63.245:443'
        '?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality'
        '&sni=pl01.appgw.store&fp=firefox'
        '&pbk=bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ'
        '&sid=45ee70d18247e5#PL01';

    Map<String, dynamic> buildFor(String url) {
      final built = VlessLinkConfigBuilder.build(VlessConfig.fromUrl(url));
      expect(built, isNotNull);
      return jsonDecode(built!) as Map<String, dynamic>;
    }

    test('the URL form now yields the same outbound the panel JSON ships', () {
      // This is the second half of the "connected but no traffic" bug: the
      // plugin's own URL builder disabled sniffing, emitted the illegal
      // routing domainStrategy "UseIp" and polluted the user/REALITY objects.
      final config = buildFor(realityUrl);
      final outbound = (config['outbounds'] as List).first as Map;
      final user = _firstUser(config);
      final stream =
          Map<String, dynamic>.from(outbound['streamSettings'] as Map);
      final reality =
          Map<String, dynamic>.from(stream['realitySettings'] as Map);

      expect(user, {
        'id': '7c08d043-2185-4fdb-afa1-b57375576e0d',
        'encryption': 'none',
        'flow': 'xtls-rprx-vision',
      });
      expect(reality, {
        'serverName': 'pl01.appgw.store',
        'publicKey': 'bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ',
        'shortId': '45ee70d18247e5',
        'fingerprint': 'firefox',
      });
      expect(stream['security'], 'reality');
      expect(stream['network'], 'tcp');
      expect(stream['tcpSettings'], isA<Map>());
    });

    test('sniffing is enabled and the SOCKS inbound sits on the native port',
        () {
      final config = buildFor(realityUrl);
      final socks = (config['inbounds'] as List).first as Map;
      expect(socks['protocol'], 'socks');
      expect(socks['port'], XrayLocalPorts.socks);
      final sniffing = Map<String, dynamic>.from(socks['sniffing'] as Map);
      expect(sniffing['enabled'], isTrue);
      expect(sniffing['destOverride'], containsAll(['http', 'tls', 'quic']));
    });

    test('routing uses a legal domainStrategy, not the plugin\'s "UseIp"', () {
      final routing =
          Map<String, dynamic>.from(buildFor(realityUrl)['routing'] as Map);
      expect(routing['domainStrategy'], 'IPIfNonMatch');
      final rules = routing['rules'] as List;
      expect(
        rules.any((r) =>
            (r as Map)['outboundTag'] == 'direct' &&
            (r['protocol'] as List).contains('bittorrent')),
        isTrue,
      );
    });

    test('dns resolves over TCP first, then the panel resolvers', () {
      final dns = Map<String, dynamic>.from(buildFor(realityUrl)['dns'] as Map);
      expect(dns['servers'],
          ['tcp://1.1.1.1', 'tcp://1.0.0.1', '1.1.1.1', '1.0.0.1']);
      expect(dns['queryStrategy'], 'UseIP');
    });

    test('tls links produce tlsSettings without REALITY junk', () {
      final config = buildFor(
          'vless://u-1@example.com:443?security=tls&type=tcp&sni=example.com&fp=chrome#T');
      final stream = Map<String, dynamic>.from(
          ((config['outbounds'] as List).first as Map)['streamSettings'] as Map);
      expect(stream['security'], 'tls');
      expect(stream.containsKey('realitySettings'), isFalse);
      final tls = Map<String, dynamic>.from(stream['tlsSettings'] as Map);
      expect(tls['serverName'], 'example.com');
      expect(tls['allowInsecure'], isFalse);
    });

    test('a REALITY link without sni imitates the address itself', () {
      final config = buildFor(
          'vless://u-2@5.6.7.8:443?security=reality&type=tcp&pbk=k#R');
      final stream = Map<String, dynamic>.from(
          ((config['outbounds'] as List).first as Map)['streamSettings'] as Map);
      final reality =
          Map<String, dynamic>.from(stream['realitySettings'] as Map);
      expect(reality['serverName'], '5.6.7.8');
      expect(reality['fingerprint'], 'chrome');
    });

    test('non-TCP transports are left to the plugin builder', () {
      expect(
        VlessLinkConfigBuilder.build(
            VlessConfig.fromUrl('vless://u-3@h.com:443?type=ws&security=tls#W')),
        isNull,
      );
      expect(
        VlessLinkConfigBuilder.build(
            VlessConfig.fromUrl('vless://u-4@h.com:443?type=grpc#G')),
        isNull,
      );
    });

    test('non-VLESS protocols are left to their own engines', () {
      expect(
        VlessLinkConfigBuilder.build(
            VlessConfig.fromUrl('ss://YWVzOnB3@1.2.3.4:8388#S')),
        isNull,
      );
    });
  });

  group('VlessConfig identity — duplicate server rows', () {
    test('the same server as JSON and as a URL share one id', () {
      // This is what produced two rows for one server, both matching the
      // selection check `selectedServer?.id == server.id`.
      final fromJson = VlessConfig.fromJsonConfig(
        jsonDecode(_panelRealityConfig) as Map<String, dynamic>,
      );
      final fromUrl = VlessConfig.fromUrl(
        'vless://7c08d043-2185-4fdb-afa1-b57375576e0d@194.93.63.245:443'
        '?security=reality&type=tcp&flow=xtls-rprx-vision'
        '&sni=pl01.appgw.store&fp=firefox'
        '&pbk=bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ'
        '&sid=45ee70d18247e5#PL01',
      );

      expect(fromJson.id, fromUrl.id);
      expect(fromJson, fromUrl);
      expect(<VlessConfig>{fromJson, fromUrl}.length, 1);
    });

    test('different endpoints keep different ids', () {
      final a = VlessConfig.fromUrl('vless://uuid-a@1.2.3.4:443#A');
      final b = VlessConfig.fromUrl('vless://uuid-b@1.2.3.4:443#B');
      final c = VlessConfig.fromUrl('vless://uuid-a@1.2.3.4:8443#C');

      expect(a.id, isNot(b.id));
      expect(a.id, isNot(c.id), reason: 'port is part of the identity');
      expect(<VlessConfig>{a, b, c}.length, 3);
    });

    test('a missing port falls back to 443 rather than 0', () {
      expect(VlessConfig.fromUrl('vless://uuid@1.2.3.4#X').port, 443);
    });

    test('dedupe collapses the JSON and URL forms into one server', () {
      final fromJson = VlessConfig.fromJsonConfig(
        jsonDecode(_panelRealityConfig) as Map<String, dynamic>,
      );
      final fromUrl = VlessConfig.fromUrl(
        'vless://7c08d043-2185-4fdb-afa1-b57375576e0d@194.93.63.245:443'
        '?security=reality&type=tcp#PL01',
      );

      final deduped = VlessConfig.dedupe(<VlessConfig>[fromUrl, fromJson]);
      expect(deduped.length, 1);

      // The JSON form wins because it alone carries routing and balancer data,
      // and it inherits the URL as a reconstruction fallback.
      expect(deduped.single.hasJsonConfig, isTrue);
      expect(deduped.single.originalUrl, fromUrl.originalUrl);
    });

    test('dedupe preserves the original ordering', () {
      final a = VlessConfig.fromUrl('vless://u@1.1.1.1:443#A');
      final b = VlessConfig.fromUrl('vless://u@2.2.2.2:443#B');
      final c = VlessConfig.fromUrl('vless://u@3.3.3.3:443#C');

      final deduped = VlessConfig.dedupe(<VlessConfig>[a, b, a, c, b]);
      expect(deduped.map((s) => s.serverName).toList(), <String>['A', 'B', 'C']);
    });

    test('dedupe leaves a list without duplicates untouched', () {
      final servers = <VlessConfig>[
        VlessConfig.fromUrl('vless://u@1.1.1.1:443#A'),
        VlessConfig.fromUrl('vless://u@2.2.2.2:443#B'),
      ];
      expect(VlessConfig.dedupe(servers).length, 2);
    });

    test('fromJsonConfig keeps the REALITY fingerprint and shortId', () {
      final config = VlessConfig.fromJsonConfig(
        jsonDecode(_panelRealityConfig) as Map<String, dynamic>,
      );
      expect(config.fp, 'firefox');
      expect(config.sid, '45ee70d18247e5');
      expect(config.pbk, 'bwe83fJSaaBDQbJbsKVi24fSJwTwdMiXVf4oceMTTAQ');
      expect(config.flow, 'xtls-rprx-vision');
      expect(config.security, 'reality');
      expect(config.port, 443);
    });
  });
}
