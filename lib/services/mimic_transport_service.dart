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

  static const int socksProxyPort = 7101;
  static const int httpProxyPort = 7102;

  bool _enabled = ApiConfig.mimicEnabled;
  String _server = ApiConfig.mimicServer;
  String _secret = ApiConfig.mimicSecret;
  List<String> _hostSuffixes = List.of(ApiConfig.mimicHostSuffixes);

  bool _started = false;
  bool _tunnelUp = false;
  bool _probing = false;
  DateTime _cooldownUntil = DateTime.fromMillisecondsSinceEpoch(0);
  Timer? _probeTimer;

  /// Текущее решение о маршрутизации для запроса на [uri].
  String routeFor(Uri uri) {
    final usable = shouldRoute(
      host: uri.host,
      enabled: _enabled,
      tunnelUp: _tunnelUp,
      cooldownUntil: _cooldownUntil,
      hostSuffixes: _hostSuffixes,
    );
    return usable ? 'PROXY 127.0.0.1:$httpProxyPort' : 'DIRECT';
  }

  /// Чистая логика решения — вынесена для тестов.
  @visibleForTesting
  static bool shouldRoute({
    required String host,
    required bool enabled,
    required bool tunnelUp,
    required DateTime cooldownUntil,
    required List<String> hostSuffixes,
    DateTime? now,
  }) {
    if (!enabled || !tunnelUp || host.isEmpty) return false;
    if ((now ?? DateTime.now()).isBefore(cooldownUntil)) return false;
    final h = host.toLowerCase();
    return hostSuffixes.any((s) {
      final suffix = s.trim().toLowerCase();
      return suffix.isNotEmpty && (h == suffix || h.endsWith('.$suffix'));
    });
  }

  /// Запуск при старте приложения. Android-only: движок собран как AAR.
  Future<void> start() async {
    if (_started || !Platform.isAndroid || !_enabled) return;
    _started = true;
    await _startEngine();
    _probeTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_probe());
    });
  }

  /// Применяет параметры из Remote Config. Смена сервера/секрета/флага
  /// перезапускает движок; список хостов применяется без рестарта.
  Future<void> applyConfig({
    String? server,
    String? secret,
    bool? enabled,
    List<String>? hostSuffixes,
  }) async {
    final restartNeeded = (server != null && server != _server) ||
        (secret != null && secret != _secret) ||
        (enabled != null && enabled != _enabled);
    if (server != null && server.isNotEmpty) _server = server;
    if (secret != null && secret.isNotEmpty) _secret = secret;
    if (enabled != null) _enabled = enabled;
    if (hostSuffixes != null &&
        hostSuffixes.any((h) => h.trim().isNotEmpty)) {
      _hostSuffixes = hostSuffixes;
    }

    if (!restartNeeded || !Platform.isAndroid) return;
    await stopEngine();
    _started = false;
    _tunnelUp = false;
    await start();
  }

  /// Сигнал от [ApiHostService]: запрос упал по соединению. Убираем туннель
  /// с приоритета и быстро перепроверяем — на случай мёртвой сессии.
  void noteRequestFailure() {
    if (!_started || !_tunnelUp) return;
    _tunnelUp = false;
    _cooldownUntil = DateTime.now().add(const Duration(minutes: 2));
    Timer(const Duration(seconds: 5), () => unawaited(_probe()));
  }

  Future<void> _startEngine() async {
    try {
      await _channel.invokeMethod<void>('start', <String, dynamic>{
        'server': _server,
        'secret': _secret,
        'domains': 'vk.com,dzen.ru,rutube.ru,telegram.org',
        'socksPort': socksProxyPort,
        'httpPort': httpProxyPort,
      });
    } on PlatformException catch (e) {
      debugPrint('MimicTransportService: engine start failed: ${e.message}');
    }
    await _probe();
  }

  Future<void> stopEngine() async {
    _probeTimer?.cancel();
    _probeTimer = null;
    try {
      await _channel.invokeMethod<void>('stop');
    } on PlatformException catch (e) {
      debugPrint('MimicTransportService: engine stop failed: ${e.message}');
    }
  }

  /// Проба end-to-end: SOCKS5 CONNECT до API-хоста через локальный порт
  /// движка. Успех = жива вся цепочка до прод-сервера; рекурсии в
  /// HttpOverrides нет, потому что это голый сокет, не HTTP-клиент.
  Future<void> _probe() async {
    if (_probing || !_enabled || !_started) return;
    _probing = true;
    try {
      final host = Uri.parse(ApiConfig.baseUrl).host;
      final ok = host.isNotEmpty && await _socks5Probe(host, 443);
      _tunnelUp = ok;
      _cooldownUntil = ok
          ? DateTime.fromMillisecondsSinceEpoch(0)
          : DateTime.now().add(const Duration(minutes: 2));
    } finally {
      _probing = false;
    }
  }

  Future<bool> _socks5Probe(String host, int port) async {
    Socket? socket;
    StreamIterator<List<int>>? reader;
    try {
      socket = await Socket.connect(
        InternetAddress.loopbackIPv4,
        socksProxyPort,
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
        5, 1, 0, 3, hostBytes.length, ...hostBytes, port >> 8, port & 0xFF,
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
