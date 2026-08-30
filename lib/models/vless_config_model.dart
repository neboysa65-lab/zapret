import 'dart:convert';

class VlessConfig {
  final String id;
  final String serverName;
  final String address;
  final int port;
  final String uuid;
  final String country;
  final String flag;
  final int ping;
  final bool isActive;

  // Original VLESS URL — used for direct connection without reconstruction
  final String originalUrl;

  // Raw Xray JSON config (with routing.balancers, multiple outbounds, etc.).
  // When non-null/non-empty, takes precedence over originalUrl during connect.
  final String? rawJsonConfig;

  // VLESS specific fields
  final String protocol; // 'vless', 'ss', 'hysteria2', 'vlee'
  final String security; // 'reality', 'tls', etc.
  final String type; // 'tcp', 'grpc', etc.
  final String flow; // 'xtls-rprx-vision'
  final String sni;
  final String fp; // fingerprint
  final String pbk; // public key
  final String sid; // short id
  final String spx; // spider x

  // Hysteria2 specific fields (handled by the separate ported engine,
  // NOT by flutter_v2ray_plus). For Hysteria2 [uuid] carries the auth password.
  final String obfs; // e.g. 'salamander' (empty = none)
  final String obfsPassword;
  final bool allowInsecure;

  bool get hasJsonConfig => rawJsonConfig != null && rawJsonConfig!.isNotEmpty;

  /// True when this server must be handled by the dedicated Hysteria2 engine.
  bool get isHysteria2 => protocol == 'hysteria2';

  /// True when this server must be handled by the VLEE engine
  /// (SSH-tunnel protocol, ported from the GW VPN app).
  bool get isVlee => protocol == 'vlee';

  /// Canonical `hysteria2://` URI reconstructed from the model fields.
  ///
  /// This is what gets passed to the native Hysteria2 engine, which rebuilds
  /// the Xray JSON itself (with the correct local SOCKS port). Works whether
  /// the server originated from a `hysteria2://` subscription line or from a
  /// backend Xray JSON config.
  String get hysteria2Url {
    final params = <String, String>{};
    if (sni.isNotEmpty) params['sni'] = sni;
    if (obfs.isNotEmpty) {
      params['obfs'] = obfs;
      if (obfsPassword.isNotEmpty) params['obfs-password'] = obfsPassword;
    }
    if (allowInsecure) params['insecure'] = '1';

    final query = params.entries
        .map((e) => '${e.key}=${Uri.encodeQueryComponent(e.value)}')
        .join('&');
    final auth = Uri.encodeComponent(uuid);
    final base = 'hysteria2://$auth@$address:$port';
    return query.isEmpty ? base : '$base?$query';
  }

  /// Clean display name without extra info like "осталось дней"
  String get cleanName {
    // Remove everything after | if present
    if (serverName.contains('|')) {
      return serverName.split('|').first.trim();
    }
    return serverName;
  }

  VlessConfig({
    required this.id,
    required this.serverName,
    required this.address,
    required this.port,
    required this.uuid,
    this.originalUrl = '',
    this.rawJsonConfig,
    this.country = 'Unknown',
    this.flag = '🏳️',
    this.ping = 0,
    this.isActive = false,
    this.protocol = 'vless',
    this.security = '',
    this.type = 'tcp',
    this.flow = '',
    this.sni = '',
    this.fp = '',
    this.pbk = '',
    this.sid = '',
    this.spx = '',
    this.obfs = '',
    this.obfsPassword = '',
    this.allowInsecure = false,
  });

  /// Parse a `hysteria2://` / `hy2://` subscription line.
  ///
  /// Official URI form (same as gwapk's VlessParser.parseHysteria2Url):
  /// hysteria2://password@host:port/?sni=...&obfs=salamander&obfs-password=...#name
  factory VlessConfig.fromHysteria2Url(String url) {
    final uri = Uri.parse(url);
    if (uri.scheme != 'hysteria2' && uri.scheme != 'hy2') {
      throw Exception('Invalid hysteria2 scheme');
    }

    final password = Uri.decodeComponent(uri.userInfo);
    if (password.isEmpty) throw Exception('Hysteria2 URL missing password');
    final address = uri.host;
    if (address.isEmpty) throw Exception('Hysteria2 URL missing host');
    final port = uri.hasPort ? uri.port : 443;

    final q = uri.queryParameters;
    final sni = q['sni'] ?? q['peer'] ?? address;
    final insecure = q['insecure'] ?? q['allowInsecure'] ?? '';
    final allowInsecure =
        insecure == '1' || insecure.toLowerCase() == 'true';
    final obfs = q['obfs'] ?? '';
    final obfsPassword =
        q['obfs-password'] ?? q['obfsPassword'] ?? q['obfs_password'] ?? '';

    String serverName = 'Hysteria2 $address';
    if (uri.hasFragment) {
      serverName = Uri.decodeComponent(uri.fragment).trim();
    }

    return VlessConfig(
      id: canonicalId(
        protocol: 'hysteria2',
        address: address,
        port: port,
        uuid: password,
      ),
      serverName: serverName,
      address: address,
      port: port,
      uuid: password, // auth password lives in the uuid slot
      originalUrl: url,
      country: _extractCountry(serverName),
      flag: _extractFlag(serverName),
      protocol: 'hysteria2',
      security: 'tls',
      type: 'hysteria',
      sni: sni,
      fp: q['fp'] ?? q['fingerprint'] ?? '',
      obfs: obfs,
      obfsPassword: obfsPassword,
      allowInsecure: allowInsecure,
    );
  }

  /// Build VlessConfig from a decrypted VLEE server envelope.
  ///
  /// [config] — plaintext конверта: компактные ключи k0..ka (или legacy-имена,
  /// обе схемы понимает и сервер, и нативный движок). Схема полей 1:1 с GW:
  ///   k0 id, k1 ip_address, k2 ssh_port, k3 ssh_username, k4 ssh_password,
  ///   k5 proxy_host, k6 proxy_port, k7 proxy_scheme, k8 payload, k9 sni,
  ///   ka ssh_hostkey.
  ///
  /// [address]/[port] для отображения и TCP-пинга — это эндпоинт, к которому
  /// клиент реально подключается: прокси/CDN-фронт, если задан, иначе SSH-нода.
  factory VlessConfig.fromVlee({
    required Map<String, dynamic> meta,
    required Map<String, dynamic> config,
  }) {
    String pick(String compact, String legacy, [String fallback = '']) =>
        config.containsKey(compact)
            ? config[compact]?.toString() ?? fallback
            : (config[legacy]?.toString() ?? fallback);

    int pickInt(String compact, String legacy, int fallback) {
      final raw = config.containsKey(compact)
          ? config[compact]
          : config[legacy];
      return (raw is num) ? raw.toInt() : int.tryParse('$raw') ?? fallback;
    }

    final nodeId = pick('k0', 'id');
    final ipAddress = pick('k1', 'ip_address');
    final sshPort = pickInt('k2', 'ssh_port', 22);
    final proxyHost = pick('k5', 'proxy_host');
    final proxyPort = pickInt('k6', 'proxy_port', 80);
    final sni = pick('k9', 'sni');
    final viaProxy = proxyHost.isNotEmpty;
    final address = viaProxy ? proxyHost : ipAddress;
    final port = viaProxy ? proxyPort : sshPort;

    final country = (meta['country'] as String?) ?? '';
    final state = (meta['state'] as String?) ?? '';
    final countryCode = (meta['country_code'] as String?) ?? '';
    final nodeName = (meta['name'] as String?) ?? '';

    String display = country.isNotEmpty && country != '0'
        ? country
        : (nodeName.isNotEmpty ? nodeName : ipAddress);
    if (state.isNotEmpty && state != '0') display = '$display · $state';

    return VlessConfig(
      id: canonicalId(
        protocol: 'vlee',
        address: ipAddress,
        port: sshPort,
        uuid: nodeId,
      ),
      serverName: display,
      address: address,
      port: port,
      uuid: '',
      originalUrl: '',
      // rawJsonConfig держит plaintext конверт (k0..ka) — нативный движок
      // принимает его как есть (VleeServerConfig.fromJson понимает обе схемы).
      rawJsonConfig: jsonEncode(config),
      country: country.isNotEmpty && country != '0' ? country : 'Unknown',
      flag: _flagFromCountryCode(countryCode),
      protocol: 'vlee',
      security: 'ssh',
      type: 'vlee',
      sni: sni,
    );
  }

  /// Код страны -> флажок-эмодзи (regional indicator symbols).
  /// Особые имена (northern_cyprus, flag_usa, ...) и мусор -> белый флаг.
  static String _flagFromCountryCode(String code) {
    final cc = code.trim().toLowerCase();
    if (cc.length != 2 ||
        cc == '0' ||
        !RegExp(r'^[a-z]{2}$').hasMatch(cc)) {
      return '🏳️';
    }
    final base = 0x1F1E6;
    return String.fromCharCode(base + cc.codeUnitAt(0) - 97) +
        String.fromCharCode(base + cc.codeUnitAt(1) - 97);
  }

  /// Build VlessConfig from full Xray JSON config (with optional balancer).
  /// First `proxy` outbound is used for display address/port and ping.
  /// Connection always uses the raw JSON, preserving balancer + all outbounds.
  factory VlessConfig.fromJsonConfig(Map<String, dynamic> map) {
    final remarks = (map['remarks'] as String?) ?? 'Server';

    String address = '';
    int port = 443;
    String uuid = '';
    String security = '';
    String type = 'tcp';
    String flow = '';
    String sni = '';
    String fp = '';
    String pbk = '';
    String sid = '';
    String spx = '';

    final outbounds = (map['outbounds'] as List?) ?? const [];

    // Hysteria2 delivered as full Xray JSON (protocol "hysteria" is the
    // custom-Xray name; version 2 = Hysteria2). Such configs must NOT be fed
    // to flutter_v2ray_plus — its stock Xray core has no hysteria outbound.
    // Extract the fields and let the dedicated Hysteria2 engine rebuild its
    // own config from the canonical hysteria2:// URL.
    for (final o in outbounds) {
      if (o is Map<String, dynamic> && o['protocol'] == 'hysteria') {
        final settings = o['settings'] as Map<String, dynamic>?;
        final stream = o['streamSettings'] as Map<String, dynamic>?;
        final hySettings = stream?['hysteriaSettings'] as Map<String, dynamic>?;
        final tls = stream?['tlsSettings'] as Map<String, dynamic>?;
        final obfsObj = hySettings?['obfs'] as Map<String, dynamic>?;

        final hyAddress = (settings?['address'] as String?) ?? '';
        final hyPort = (settings?['port'] as num?)?.toInt() ?? 443;
        final hyAuth = (hySettings?['auth'] as String?) ?? '';
        final hySni = (tls?['serverName'] as String?) ?? hyAddress;

        return VlessConfig(
          id: canonicalId(
            protocol: 'hysteria2',
            address: hyAddress,
            port: hyPort,
            uuid: hyAuth,
          ),
          serverName: remarks,
          address: hyAddress,
          port: hyPort,
          uuid: hyAuth,
          originalUrl: '',
          // rawJsonConfig deliberately NOT kept: its inbound ports target
          // flutter_v2ray_plus (10808), while the Hysteria2 engine builds its
          // own JSON with the SOCKS port its TUN layer expects (20808).
          country: _extractCountry(remarks),
          flag: _extractFlag(remarks),
          protocol: 'hysteria2',
          security: 'tls',
          type: 'hysteria',
          sni: hySni,
          fp: (tls?['fingerprint'] as String?) ?? '',
          obfs: (obfsObj?['type'] as String?) ?? '',
          obfsPassword: (obfsObj?['password'] as String?) ?? '',
          allowInsecure: (tls?['allowInsecure'] as bool?) ?? false,
        );
      }
    }

    Map<String, dynamic>? proxyOut;
    for (final o in outbounds) {
      if (o is Map<String, dynamic> &&
          o['protocol'] == 'vless' &&
          (o['tag'] == 'proxy' || proxyOut == null)) {
        proxyOut = o;
        if (o['tag'] == 'proxy') break;
      }
    }

    if (proxyOut != null) {
      final settings = proxyOut['settings'] as Map<String, dynamic>?;
      final vnext = (settings?['vnext'] as List?) ?? const [];
      if (vnext.isNotEmpty && vnext.first is Map) {
        final v = Map<String, dynamic>.from(vnext.first as Map);
        address = _asString(v['address']);
        port = _asInt(v['port']) ?? 443;
        final users = (v['users'] as List?) ?? const [];
        if (users.isNotEmpty && users.first is Map) {
          final u = Map<String, dynamic>.from(users.first as Map);
          uuid = _asString(u['id']);
          flow = _asString(u['flow']);
        }
      }

      final stream = proxyOut['streamSettings'] as Map<String, dynamic>?;
      type = _asString(stream?['network'], fallback: 'tcp');
      security = _asString(stream?['security']);
      final reality = stream?['realitySettings'] as Map<String, dynamic>?;
      if (reality != null) {
        // REALITY carries the fingerprint and shortId too — dropping them left
        // the model unable to rebuild a working vless:// link for this server.
        sni = _asString(reality['serverName']);
        fp = _asString(reality['fingerprint']);
        pbk = _asString(reality['publicKey']);
        sid = _asString(reality['shortId']);
        spx = _asString(reality['spiderX']);
      } else {
        final tls = stream?['tlsSettings'] as Map<String, dynamic>?;
        sni = _asString(tls?['serverName']);
        fp = _asString(tls?['fingerprint']);
      }
    }

    final balancers = (map['routing']?['balancers'] as List?) ?? const [];
    final hasBalancer = balancers.isNotEmpty;

    // A balanced config has no single endpoint to key on, so it stays
    // identified by its remark. Everything else gets the canonical id, which is
    // deliberately identical to the one `fromUrl` derives — the same server
    // delivered both as JSON and as a vless:// line must collapse to one entry.
    final id = hasBalancer
        ? 'json|$remarks'
        : canonicalId(
            protocol: 'vless',
            address: address,
            port: port,
            uuid: uuid,
          );

    return VlessConfig(
      id: id,
      serverName: remarks,
      address: address,
      port: port,
      uuid: uuid,
      originalUrl: '',
      rawJsonConfig: jsonEncode(map),
      country: _extractCountry(remarks),
      flag: _extractFlag(remarks),
      protocol: 'vless',
      security: security,
      type: type,
      flow: flow,
      sni: sni,
      fp: fp,
      pbk: pbk,
      sid: sid,
      spx: spx,
    );
  }

  factory VlessConfig.fromUrl(String url) {
    // vless://uuid@host:port?params#name

    String id = '';
    String serverName = 'Server';
    String address = '';
    int port = 443;
    String uuid = '';
    String security = '';
    String type = 'tcp';
    String flow = '';
    String sni = '';
    String fp = '';
    String pbk = '';
    String sid = '';
    String spx = '';

    try {
      final uri = Uri.parse(url);
      if (uri.scheme != 'vless' && uri.scheme != 'ss') throw Exception('Invalid scheme');

      // Shadowsocks: ss://base64(method:password)@host:port#name
      if (uri.scheme == 'ss') {
        address = uri.host;
        port = uri.port;
        if (uri.hasFragment) serverName = Uri.decodeComponent(uri.fragment);
        id = canonicalId(
          protocol: 'ss',
          address: address,
          port: port,
          uuid: '',
        );
        return VlessConfig(
          id: id,
          serverName: serverName,
          address: address,
          port: port,
          uuid: '',
          originalUrl: url,
          protocol: 'ss',
          country: _extractCountry(serverName),
          flag: _extractFlag(serverName),
        );
      }

      uuid = uri.userInfo;
      address = uri.host;
      // Uri.port yields 0 for a scheme it has no default for, e.g. vless://.
      port = uri.hasPort && uri.port != 0 ? uri.port : 443;

      // Parse query parameters
      uri.queryParameters.forEach((key, value) {
        switch (key) {
          case 'security':
            security = value;
            break;
          case 'type':
            type = value;
            break;
          case 'flow':
            flow = value;
            break;
          case 'sni':
            sni = value;
            break;
          case 'fp':
            fp = value;
            break;
          case 'pbk':
            pbk = value;
            break;
          case 'sid':
            sid = value;
            break;
          case 'spx':
            spx = value;
            break;
        }
      });

      // Name is in fragment (after #)
      if (uri.hasFragment) {
        serverName = Uri.decodeComponent(uri.fragment);
      }

      // Canonical id, identical to the one `fromJsonConfig` derives, so the same
      // server arriving in both formats is recognised as one server.
      id = canonicalId(
        protocol: 'vless',
        address: address,
        port: port,
        uuid: uuid,
      );
    } catch (e) {
      print('Error parsing VLESS URL: $e');
      // Fallback or rethrow? For now, we return a broken config connection will fail but app won't crash
    }

    // Extract country/flag from name if possible (e.g. "RU 🇷🇺 | Server")
    String displayFlag = '🏳️';
    String displayCountry = 'Unknown';

    // Try to find flag emoji in server name (flags are 2 regional indicator symbols)
    // Instead of complex regex, just scan for common patterns
    final flagPattern = RegExp(
      r'[\u{1F1E6}-\u{1F1FF}][\u{1F1E6}-\u{1F1FF}]',
      unicode: true,
    );
    final match = flagPattern.firstMatch(serverName);
    if (match != null) {
      displayFlag = match.group(0) ?? '🏳️';
    }

    // Extract country from common patterns
    if (serverName.contains('RU ') ||
        serverName.contains(' RU') ||
        serverName.contains('Russia') ||
        serverName.contains('🇷🇺')) {
      displayCountry = 'Russia';
    } else if (serverName.contains('GB ') ||
        serverName.contains(' GB') ||
        serverName.contains('UK ') ||
        serverName.contains(' UK') ||
        serverName.contains('🇬🇧')) {
      displayCountry = 'United Kingdom';
    } else if (serverName.contains('US ') ||
        serverName.contains(' US') ||
        serverName.contains('USA') ||
        serverName.contains('🇺🇸')) {
      displayCountry = 'United States';
    } else if (serverName.contains('DE ') ||
        serverName.contains(' DE') ||
        serverName.contains('Germany') ||
        serverName.contains('🇩🇪')) {
      displayCountry = 'Germany';
    } else if (serverName.contains('NL ') ||
        serverName.contains(' NL') ||
        serverName.contains('Netherlands') ||
        serverName.contains('🇳🇱')) {
      displayCountry = 'Netherlands';
    } else if (serverName.contains('JP ') ||
        serverName.contains(' JP') ||
        serverName.contains('Japan') ||
        serverName.contains('🇯🇵')) {
      displayCountry = 'Japan';
    } else if (serverName.contains('SG ') ||
        serverName.contains(' SG') ||
        serverName.contains('Singapore') ||
        serverName.contains('🇸🇬')) {
      displayCountry = 'Singapore';
    } else if (serverName.contains('SP ') ||
        serverName.contains(' SP') ||
        serverName.contains('ES ') ||
        serverName.contains(' ES') ||
        serverName.contains('Spain') ||
        serverName.contains('🇪🇸')) {
      displayCountry = 'Spain';
    } else if (serverName.contains('FR ') ||
        serverName.contains(' FR') ||
        serverName.contains('France') ||
        serverName.contains('🇫🇷')) {
      displayCountry = 'France';
    } else if (serverName.contains('TR ') ||
        serverName.contains(' TR') ||
        serverName.contains('Turkey') ||
        serverName.contains('🇹🇷')) {
      displayCountry = 'Turkey';
    } else if (serverName.contains('PL ') ||
        serverName.contains(' PL') ||
        serverName.contains('Poland') ||
        serverName.contains('🇵🇱')) {
      displayCountry = 'Poland';
    } else if (serverName.contains('FI ') ||
        serverName.contains(' FI') ||
        serverName.contains('Finland') ||
        serverName.contains('🇫🇮')) {
      displayCountry = 'Finland';
    } else if (serverName.contains('SE ') ||
        serverName.contains(' SE') ||
        serverName.contains('Sweden') ||
        serverName.contains('🇸🇪')) {
      displayCountry = 'Sweden';
    }

    // Try to extract and append server number from the original name (e.g. "NL 02" -> "2")
    final matches = RegExp(r'\d+').allMatches(serverName);
    if (matches.isNotEmpty) {
      final numStr = matches.last.group(0);
      if (numStr != null) {
        final serverNum = int.tryParse(numStr);
        if (serverNum != null) {
          displayCountry = '$displayCountry $serverNum';
        }
      }
    }

    return VlessConfig(
      id: id.isNotEmpty ? id : url.hashCode.toString(),
      serverName: serverName,
      address: address,
      port: port,
      uuid: uuid,
      originalUrl: url,
      country: displayCountry,
      flag: displayFlag,
      security: security,
      type: type,
      flow: flow,
      sni: sni,
      fp: fp,
      pbk: pbk,
      sid: sid,
      spx: spx,
    );
  }

  /// Stable identity for a server endpoint.
  ///
  /// Every factory routes through this so that the *same* server is given the
  /// *same* id no matter which format the subscription delivered it in. The
  /// server list keys its selection highlight off this id, so two rows sharing
  /// one id is exactly what made a single tap appear to select two servers.
  static String canonicalId({
    required String protocol,
    required String address,
    required int port,
    required String uuid,
  }) {
    final normalizedProtocol = protocol.trim().toLowerCase();
    final normalizedAddress = address.trim().toLowerCase();
    return '$normalizedProtocol|$normalizedAddress|$port|${uuid.trim()}';
  }

  /// Collapses entries that describe the same server into one.
  ///
  /// A subscription commonly delivers one server twice — once as a `vless://`
  /// line and once as a full Xray JSON config. Both parse to the same
  /// [canonicalId], so the list rendered two rows for a single server and, since
  /// the selection highlight compares ids, tapping either row lit up both.
  ///
  /// Order is preserved: the first occurrence keeps its position.
  static List<VlessConfig> dedupe(Iterable<VlessConfig> configs) {
    final Map<String, VlessConfig> unique = <String, VlessConfig>{};
    for (final config in configs) {
      final existing = unique[config.id];
      unique[config.id] =
          existing == null ? config : _mergeDuplicate(existing, config);
    }
    return unique.values.toList(growable: false);
  }

  /// Resolves two entries for the same endpoint.
  ///
  /// The richer form wins: a full JSON config carries routing and balancer
  /// information a bare URL cannot express. Whatever the other form still
  /// contributes — usually the original URL, worth keeping as a fallback for
  /// link reconstruction — is folded into the winner.
  static VlessConfig _mergeDuplicate(
    VlessConfig existing,
    VlessConfig candidate,
  ) {
    final VlessConfig winner;
    final VlessConfig loser;
    if (existing.hasJsonConfig || !candidate.hasJsonConfig) {
      winner = existing;
      loser = candidate;
    } else {
      winner = candidate;
      loser = existing;
    }

    if (winner.originalUrl.isEmpty && loser.originalUrl.isNotEmpty) {
      return winner.copyWith(originalUrl: loser.originalUrl);
    }
    return winner;
  }

  static String _asString(dynamic value, {String fallback = ''}) {
    if (value == null) return fallback;
    if (value is String) return value.isEmpty ? fallback : value;
    return value.toString();
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value.trim());
    return null;
  }

  @override
  bool operator ==(Object other) =>
      other is VlessConfig && other.id == id;

  @override
  int get hashCode => id.hashCode;

  static String _extractFlag(String name) {
    final match = RegExp(
      r'[\u{1F1E6}-\u{1F1FF}][\u{1F1E6}-\u{1F1FF}]',
      unicode: true,
    ).firstMatch(name);
    return match?.group(0) ?? '🏳️';
  }

  static String _extractCountry(String name) {
    if (name.contains('🇷🇺') || name.contains('Russia') || name.contains('RU ') || name.contains('Россия')) return 'Russia';
    if (name.contains('🇬🇧') || name.contains('UK ') || name.contains('GB ') || name.contains('Britain')) return 'United Kingdom';
    if (name.contains('🇺🇸') || name.contains('USA') || name.contains('US ')) return 'United States';
    if (name.contains('🇩🇪') || name.contains('Germany') || name.contains('DE ') || name.contains('Германия')) return 'Germany';
    if (name.contains('🇳🇱') || name.contains('Netherlands') || name.contains('NL ') || name.contains('Нидерланды')) return 'Netherlands';
    if (name.contains('🇯🇵') || name.contains('Japan') || name.contains('JP ') || name.contains('Япония')) return 'Japan';
    if (name.contains('🇸🇬') || name.contains('Singapore') || name.contains('SG ')) return 'Singapore';
    if (name.contains('🇫🇷') || name.contains('France') || name.contains('FR ') || name.contains('Франция')) return 'France';
    if (name.contains('🇫🇮') || name.contains('Finland') || name.contains('FI ') || name.contains('Финляндия')) return 'Finland';
    if (name.contains('🇸🇪') || name.contains('Sweden') || name.contains('SE ') || name.contains('Швеция')) return 'Sweden';
    if (name.contains('🇵🇱') || name.contains('Poland') || name.contains('PL ') || name.contains('Польша')) return 'Poland';
    if (name.contains('🇹🇷') || name.contains('Turkey') || name.contains('TR ') || name.contains('Турция')) return 'Turkey';
    if (name.contains('🇪🇸') || name.contains('Spain') || name.contains('ES ') || name.contains('Испания')) return 'Spain';
    if (name.contains('🇨🇭') || name.contains('Switzerland') || name.contains('CH ') || name.contains('Швейцария')) return 'Switzerland';
    if (name.contains('🇦🇹') || name.contains('Austria') || name.contains('AT ') || name.contains('Австрия')) return 'Austria';
    if (name.contains('🇧🇪') || name.contains('Belgium') || name.contains('BE ') || name.contains('Бельгия')) return 'Belgium';
    if (name.contains('🇱🇻') || name.contains('Latvia') || name.contains('LV ') || name.contains('Латвия')) return 'Latvia';
    if (name.contains('🇱🇹') || name.contains('Lithuania') || name.contains('LT ') || name.contains('Литва')) return 'Lithuania';
    if (name.contains('🇪🇪') || name.contains('Estonia') || name.contains('EE ') || name.contains('Эстония')) return 'Estonia';
    if (name.contains('🇳🇴') || name.contains('Norway') || name.contains('NO ') || name.contains('Норвегия')) return 'Norway';
    if (name.contains('🇭🇺') || name.contains('Hungary') || name.contains('HU ') || name.contains('Венгрия')) return 'Hungary';
    if (name.contains('🇷🇴') || name.contains('Romania') || name.contains('RO ') || name.contains('Румыния')) return 'Romania';
    if (name.contains('🇨🇿') || name.contains('Czech') || name.contains('CZ ') || name.contains('Чехия')) return 'Czech Republic';
    return 'Unknown';
  }

  VlessConfig copyWith({
    String? id,
    String? serverName,
    String? address,
    int? port,
    String? uuid,
    String? originalUrl,
    String? rawJsonConfig,
    String? country,
    String? flag,
    int? ping,
    bool? isActive,
    String? protocol,
    String? security,
    String? type,
    String? flow,
    String? sni,
    String? fp,
    String? pbk,
    String? sid,
    String? spx,
    String? obfs,
    String? obfsPassword,
    bool? allowInsecure,
  }) {
    return VlessConfig(
      id: id ?? this.id,
      serverName: serverName ?? this.serverName,
      address: address ?? this.address,
      port: port ?? this.port,
      uuid: uuid ?? this.uuid,
      originalUrl: originalUrl ?? this.originalUrl,
      rawJsonConfig: rawJsonConfig ?? this.rawJsonConfig,
      country: country ?? this.country,
      flag: flag ?? this.flag,
      ping: ping ?? this.ping,
      isActive: isActive ?? this.isActive,
      protocol: protocol ?? this.protocol,
      security: security ?? this.security,
      type: type ?? this.type,
      flow: flow ?? this.flow,
      sni: sni ?? this.sni,
      fp: fp ?? this.fp,
      pbk: pbk ?? this.pbk,
      sid: sid ?? this.sid,
      spx: spx ?? this.spx,
      obfs: obfs ?? this.obfs,
      obfsPassword: obfsPassword ?? this.obfsPassword,
      allowInsecure: allowInsecure ?? this.allowInsecure,
    );
  }
}
