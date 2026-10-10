import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../config/api_config.dart';

/// Второй транспорт к API: MTP (Mimic Protocol) поверх UDP, шифрование
/// ChaCha20-Poly1305. Домен для него не нужен — только IP и секрет, поэтому
/// блокировка HTTPS-домена РКН на этот канал не влияет.
///
/// Приоритет маршрутов: туннель → обычный HTTPS (фолбек). Пока туннель
/// поднят и хост в белом списке, [MimicHttpOverrides] направляет
/// dart:io-запросы через локальный HTTP-прокси движка. Любой сбой —
/// кулдаун, и трафик уходит прямым HTTPS, не дожидаясь починки.
class MimicTransportService {
  MimicTransportService._();

  static final MimicTransportService instance = MimicTransportService._();

  static const _channel = MethodChannel('app.vless.vpn/mimic');

  // Порты локальных прокси движков: udp → 7101/7102, tcp → 7103/7104.
  static const int udpSocksPort = 7101;
  static const int udpHttpPort = 7102;
  static const int tcpSocksPort = 7103;
  static const int tcpHttpPort = 7104;

  bool _enabled = ApiConfig.mimicEnabled;
  String _server = ApiConfig.mimicServer;
  String _tcpServer = ApiConfig.mimicTcpServer;
  String _tcpPin = ApiConfig.mimicTcpPin;
  String _secret = ApiConfig.mimicSecret;
  List<String> _hostSuffixes = List.of(ApiConfig.mimicHostSuffixes);

  bool _udpStarted = false;
  bool _tcpStarted = false;
  bool _udpUp = false;
  bool _tcpUp = false;
  bool _probing = false;
  DateTime _udpCooldown = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _tcpCooldown = DateTime.fromMillisecondsSinceEpoch(0);
  Timer? _probeTimer;

  /// Текущее решение о маршрутизации для запроса на [uri]:
  /// udp-туннель → tcp-туннель → прямой HTTPS.
  String routeFor(Uri uri) {
    final decision = decide(
      host: uri.host,
      enabled: _enabled,
      udpUp: _udpUp,
      tcpUp: _tcpUp,
      udpCooldown: _udpCooldown,
      tcpCooldown: _tcpCooldown,
      hostSuffixes: _hostSuffixes,
    );
    return switch (decision) {
      'udp' => 'PROXY 127.0.0.1:$udpHttpPort',
      'tcp' => 'PROXY 127.0.0.1:$tcpHttpPort',
      _ => 'DIRECT',
    };
  }

  /// Чистая логика решения — вынесена для тестов.
  @visibleForTesting
  static String decide({
    required String host,
    required bool enabled,
    required bool udpUp,
    required bool tcpUp,
    required DateTime udpCooldown,
    required DateTime tcpCooldown,
    required List<String> hostSuffixes,
    DateTime? now,
  }) {
    if (!enabled || host.isEmpty) return 'direct';
    final h = host.toLowerCase();
    final allowed = hostSuffixes.any((s) {
      final suffix = s.trim().toLowerCase();
      return suffix.isNotEmpty && (h == suffix || h.endsWith('.$suffix'));
    });
    if (!allowed) return 'direct';
    final t = now ?? DateTime.now();
    if (udpUp && !t.isBefore(udpCooldown)) return 'udp';
    if (tcpUp && !t.isBefore(tcpCooldown)) return 'tcp';
    return 'direct';
  }

  /// Запуск при старте приложения. Android-only: движок собран как AAR.
  /// UDP-туннель стартует сразу, TCP-фолбек — лениво при первом сбое UDP.
  Future<void> start() async {
    if (!Platform.isAndroid || !_enabled) return;
    await _ensureEngine('udp');
    _probeTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_probe());
    });
    unawaited(_probe());
  }

  /// Применяет параметры из Remote Config. Смена сервера/секрета/флага
  /// перезапускает соответствующий движок; список хостов — без рестарта.
  Future<void> applyConfig({
    String? server,
    String? tcpServer,
    String? tcpPin,
    String? secret,
    bool? enabled,
    List<String>? hostSuffixes,
  }) async {
    final udpRestart = (server != null && server != _server) ||
        (secret != null && secret != _secret) ||
        (enabled != null && enabled != _enabled);
    final tcpRestart =
        (tcpServer != null && tcpServer != _tcpServer) ||
            (tcpPin != null && tcpPin != _tcpPin) ||
            (secret != null && secret != _secret);
    if (server != null && server.isNotEmpty) _server = server;
    if (tcpServer != null && tcpServer.isNotEmpty) _tcpServer = tcpServer;
    if (tcpPin != null && tcpPin.isNotEmpty) _tcpPin = tcpPin;
    if (secret != null && secret.isNotEmpty) _secret = secret;
    if (enabled != null) _enabled = enabled;
    if (hostSuffixes != null &&
        hostSuffixes.any((h) => h.trim().isNotEmpty)) {
      _hostSuffixes = hostSuffixes;
    }

    if (!_enabled) {
      // Kill-switch из Remote Config: глушим оба движка и пробник.
      _probeTimer?.cancel();
      _probeTimer = null;
      _udpUp = _tcpUp = false;
      _udpStarted = _tcpStarted = false;
      await stopEngine('udp');
      await stopEngine('tcp');
      return;
    }

    if (!Platform.isAndroid) return;
    if (udpRestart) {
      await stopEngine('udp');
      _udpStarted = false;
      _udpUp = false;
      await _ensureEngine('udp');
    }
    if (tcpRestart && _tcpStarted) {
      await stopEngine('tcp');
      _tcpStarted = false;
      _tcpUp = false;
    }
  }

  /// Сигнал от [ApiHostService]: запрос упал по соединению. Убираем туннель
  /// с приоритета и быстро перепроверяем — на случай мёртвой сессии.
  void noteRequestFailure() {
    if (!_udpUp && !_tcpUp) return;
    final active = _udpUp ? 'udp' : 'tcp';
    _markDown(active);
    Timer(const Duration(seconds: 5), () => unawaited(_probe()));
  }

  Future<bool> _ensureEngine(String transport) async {
    if (!_enabled) return false;
    if (transport == 'udp') {
      if (_udpStarted) return true;
      _udpStarted = await _startEngine(transport, _server, null);
      return _udpStarted;
    }
    if (_tcpStarted) return true;
    if (_tcpServer.isEmpty || _tcpPin.isEmpty) return false;
    _tcpStarted = await _startEngine(transport, _tcpServer, _tcpPin);
    return _tcpStarted;
  }

  Future<bool> _startEngine(
    String transport,
    String server,
    String? pin,
  ) async {
    try {
      await _channel.invokeMethod<void>('start', <String, dynamic>{
        'server': server,
        'transport': transport,
        'pin': pin ?? '',
        'secret': _secret,
        'domains': 'vk.com,dzen.ru,rutube.ru,telegram.org',
        'socksPort':
            transport == 'udp' ? udpSocksPort : tcpSocksPort,
        'httpPort': transport == 'udp' ? udpHttpPort : tcpHttpPort,
      });
      return true;
    } on PlatformException catch (e) {
      debugPrint('MimicTransportService: engine start failed: ${e.message}');
      return false;
    }
  }

  Future<void> stopEngine(String transport) async {
    try {
      await _channel.invokeMethod<void>('stop', <String, dynamic>{
        'transport': transport,
      });
    } on PlatformException catch (e) {
      debugPrint('MimicTransportService: engine stop failed: ${e.message}');
    }
  }

  /// Проба end-to-end: SOCKS5 CONNECT до API-хоста через локальный порт
  /// движка. Успех = жива вся цепочка до прод-сервера; рекурсии в
  /// HttpOverrides нет, потому что это голый сокет, не HTTP-клиент.
  Future<void> _probe() async {
    if (_probing || !_enabled) return;
    _probing = true;
    try {
      final host = Uri.parse(ApiConfig.baseUrl).host;
      if (host.isEmpty) return;

      // Активный транспорт перепроверяем каждый цикл: молча умершая сессия
      // не должна оставаться наверху цепочки.
      if (_udpUp && !await _socks5Probe(host, 443, udpSocksPort)) {
        _markDown('udp');
      }
      if (!_udpUp) {
        final udpOk = await _ensureEngine('udp') &&
            await _socks5Probe(host, 443, udpSocksPort);
        if (udpOk) {
          _udpUp = true;
          _udpCooldown = DateTime.fromMillisecondsSinceEpoch(0);
        } else {
          _markDown('udp');
        }
      }
      if (_tcpUp && !await _socks5Probe(host, 443, tcpSocksPort)) {
        _markDown('tcp');
      }
      if (!_udpUp && !_tcpUp) {
        final tcpOk = await _ensureEngine('tcp') &&
            await _socks5Probe(host, 443, tcpSocksPort);
        if (tcpOk) {
          _tcpUp = true;
          _tcpCooldown = DateTime.fromMillisecondsSinceEpoch(0);
        } else {
          _markDown('tcp');
        }
      }
    } finally {
      _probing = false;
    }
  }

  void _markDown(String transport) {
    final until = DateTime.now().add(const Duration(minutes: 2));
    if (transport == 'udp') {
      _udpUp = false;
      _udpCooldown = until;
      _udpStarted = false;
      unawaited(stopEngine('udp'));
    } else {
      _tcpUp = false;
      _tcpCooldown = until;
      _tcpStarted = false;
      unawaited(stopEngine('tcp'));
    }
  }

  Future<bool> _socks5Probe(String host, int targetPort, int socksPort) async {
    Socket? socket;
    StreamIterator<List<int>>? reader;
    try {
      socket = await Socket.connect(
        InternetAddress.loopbackIPv4,
        socksPort,
        timeout: const Duration(seconds: 3),
      );
      final it = StreamIterator<List<int>>(socket);
      reader = it;

      Future<List<int>> read(int count) async {
        final result = <int>[];
        while (result.length < count) {
          if (!await it.moveNext()) {
            throw const SocketException('socks5 probe: connection closed');
          }
          result.addAll(it.current);
        }
        return result.sublist(0, count);
      }

      socket.add(<int>[5, 1, 0]);
      final greeting = await read(2);
      if (greeting[0] != 5) return false;

      final hostBytes = host.codeUnits;
      socket.add(<int>[
        5, 1, 0, 3, hostBytes.length, ...hostBytes,
        targetPort >> 8, targetPort & 0xFF,
      ]);
      final head = await read(4);
      if (head[1] != 0) return false; // reply code != succeeded
      final boundLen = switch (head[3]) {
        1 => 6, // IPv4 + порт
        4 => 18, // IPv6 + порт
        3 => -1, // домен: сначала читается байт длины
        _ => -2, // неизвестный ATYP
      };
      if (boundLen == -2) return false;
      if (boundLen == -1) {
        final len = (await read(1))[0];
        await read(len + 2); // домен + порт
      } else {
        await read(boundLen);
      }
      return true;
    } on Exception {
      return false;
    } finally {
      await reader?.cancel();
      socket?.destroy();
    }
  }
}
