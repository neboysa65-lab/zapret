import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_v2ray_plus/flutter_v2ray.dart';
import 'package:flutter_v2ray_plus/url/url.dart';
import '../models/vless_config_model.dart';
import 'hysteria_engine_service.dart';
import 'vlee_engine_service.dart';
import 'vless_link_config_builder.dart';
import 'xray_config_normalizer.dart';
import 'awg_engine_service.dart';

/// VPN connection status
enum VpnStatus { disconnected, connecting, connected, disconnecting, error }

/// Which engine currently owns the tunnel.
enum ActiveEngine { none, v2ray, hysteria2, vlee, awg }

/// Service for VPN connection management.
///
/// VLESS / Shadowsocks / raw JSON configs go through flutter_v2ray_plus
/// (unchanged). Hysteria2 servers are routed to the dedicated native engine
/// (see HysteriaEngineService), ported from the gwapk project. VLEE servers
/// go to the VLEE engine (see VleeEngineService), ported from gwvpn-new.
class VpnService {
  final VleeEngineService _vleeEngine;
  final FlutterV2ray _flutterV2ray;
  final HysteriaEngineService _hysteriaEngine;
  final AwgEngineService _awgEngine;
  int _connectAttempt = 0;
  bool _stopping = false;
  bool _allowRestore = true;
  Future<bool>? _pendingStop;
  StreamSubscription<VlessStatus>? _awgStatusSubscription;
  ActiveEngine _activeEngine = ActiveEngine.none;
  VpnStatus _status = VpnStatus.disconnected;
  VlessConfig? _currentConfig;
  String? _errorMessage;
  StreamSubscription<VlessStatus>? _statusSubscription;
  StreamSubscription<HysteriaState>? _hysteriaStatusSubscription;
  StreamSubscription<HysteriaTraffic>? _hysteriaTrafficSubscription;
  HysteriaTraffic? _lastHysteriaTraffic;
  DateTime? _lastHysteriaTrafficAt;
  StreamSubscription<VleeState>? _vleeStatusSubscription;
  StreamSubscription<VleeTraffic>? _vleeTrafficSubscription;
  VleeTraffic? _lastVleeTraffic;
  DateTime? _lastVleeTrafficAt;

  // Stream controller to broadcast status changes
  final _statusController = StreamController<VpnStatus>.broadcast();
  Stream<VpnStatus> get statusStream => _statusController.stream;

  // Stream controller for full VlessStatus (including speed)
  final _vlessStatusController = StreamController<VlessStatus>.broadcast();
  Stream<VlessStatus> get vlessStatusStream => _vlessStatusController.stream;

  VpnStatus get status => _status;
  VlessConfig? get currentConfig => _currentConfig;
  String? get errorMessage => _errorMessage;

  /// Callback for status changes (kept for compatibility)
  Function(VpnStatus)? onStatusChanged;

  VpnService({
    FlutterV2ray? v2ray,
    AwgEngineService? awgEngine,
    HysteriaEngineService? hysteriaEngine,
    VleeEngineService? vleeEngine,
  }) : _flutterV2ray = v2ray ?? FlutterV2ray(),
       _hysteriaEngine = hysteriaEngine ?? HysteriaEngineService(),
       _vleeEngine = vleeEngine ?? VleeEngineService(),
       _awgEngine = awgEngine ?? AwgEngineService();

  /// Initialize VPN engine
  Future<bool> initialize() async {
    try {
      await _flutterV2ray.initializeVless(
        notificationIconResourceType: "mipmap",
        notificationIconResourceName: "ic_launcher",
      );

      // Listen to the status stream from flutter_v2ray_plus
      _statusSubscription = _flutterV2ray.onStatusChanged.listen((vlessStatus) {
        // Ignore events from an engine that no longer owns the tunnel.
        if (_stopping) return;
        if (_activeEngine == ActiveEngine.none &&
            _allowRestore &&
            vlessStatus.state == 'CONNECTED')
          _activeEngine = ActiveEngine.v2ray;
        if (_activeEngine != ActiveEngine.v2ray) return;
        _handleStatusChange(vlessStatus);
      });

      // Dedicated Hysteria2 engine: init + state / traffic streams.
      await _hysteriaEngine.initialize();
      _hysteriaStatusSubscription = _hysteriaEngine.statusStream.listen(
        _handleHysteriaStateChange,
      );
      _hysteriaTrafficSubscription = _hysteriaEngine.trafficStream.listen(
        _handleHysteriaTraffic,
      );

      _awgStatusSubscription = _awgEngine.statusStream.listen((event) {
        if (_stopping) return;
        if (_allowRestore &&
            _activeEngine == ActiveEngine.none &&
            event.state == 'CONNECTED') {
          _activeEngine = ActiveEngine.awg;
        }
        if (_activeEngine != ActiveEngine.awg) return;
        if (event.state == 'ERROR') _errorMessage = 'Ошибка AWG подключения';
        _handleStatusChange(event);
      });
      await _awgEngine.initialize();

      // VLEE engine: state / traffic streams (init не нужен).
      _vleeStatusSubscription =
          _vleeEngine.statusStream.listen(_handleVleeStateChange);
      _vleeTrafficSubscription =
          _vleeEngine.trafficStream.listen(_handleVleeTraffic);

      return true;
    } catch (e) {
      _errorMessage = 'Initialization error: $e';
      return false;
    }
  }

  /// Connect to VPN
  /// [blockedApps] — optional list of package names to exclude from VPN tunnel (split tunneling)
  Future<bool> connect(VlessConfig config, {List<String>? blockedApps}) async {
    final attemptId = ++_connectAttempt;
    _allowRestore = false;
    try {
      final pendingStop = _pendingStop;
      if (pendingStop != null && !await pendingStop) return false;
      if (attemptId != _connectAttempt) return false;
      if (!config.canConnect) {
        _errorMessage = config.locked
            ? 'Для сервера нужен Premium'
            : 'Сервер временно недоступен';
        return false;
      }
      if (_status == VpnStatus.connected ||
          _status == VpnStatus.connecting ||
          (_status == VpnStatus.error && _activeEngine != ActiveEngine.none)) {
        if (!await _disconnectEngine()) return false;
        // Give the OS time to fully tear down the previous foreground service
        // before starting a new one. Otherwise Android 12+ throws
        // ForegroundServiceDidNotStartInTimeException.
        await Future.delayed(const Duration(milliseconds: 600));
      }

      if (attemptId != _connectAttempt) return false;

      _updateStatus(VpnStatus.connecting);
      _errorMessage = null;
      _currentConfig = config;

      if (config.isAwg) {
        _activeEngine = ActiveEngine.awg;
        final started = await _awgEngine.connect(
          config,
          blockedApps: blockedApps,
        );
        if (attemptId != _connectAttempt) return false;
        if (!started) {
          _errorMessage = 'AWG: подключение отменено или нет разрешения VPN';
          _updateStatus(VpnStatus.disconnected);
        }
        return started;
      }

      // --- Hysteria2: handled by the dedicated ported engine, never by
      // flutter_v2ray_plus (its stock Xray core has no hysteria outbound). ---
      if (config.isHysteria2) {
        _activeEngine = ActiveEngine.hysteria2;
        final url =
            config.originalUrl.isNotEmpty &&
                (config.originalUrl.startsWith('hysteria2://') ||
                    config.originalUrl.startsWith('hy2://'))
            ? config.originalUrl
            : config.hysteria2Url;

        final started = await _hysteriaEngine.connect(url);
        if (attemptId != _connectAttempt) return false;
        if (!started) {
          _errorMessage = 'Не удалось запустить Hysteria2 движок.';
          _activeEngine = ActiveEngine.none;
          _updateStatus(VpnStatus.disconnected);
          return false;
        }
        // Final CONNECTED/ERROR arrives via the engine status stream.
        return true;
      }

      // --- VLEE: SSH-туннель, отдельный движок (порт gw-tunnel). ---
      // Нативная сторона сама запрашивает VpnService.prepare() и стартует
      // туннель после grant'а; прогресс приходит через statusStream.
      if (config.isVlee) {
        _activeEngine = ActiveEngine.vlee;
        final json = config.hasJsonConfig
            ? config.rawJsonConfig!
            : throw Exception('VLEE config missing raw JSON');

        final started = await _vleeEngine.connect(
          json,
          excludedApps: blockedApps ?? const [],
        );
        if (attemptId != _connectAttempt) return false;
        if (!started) {
          _errorMessage = 'Не удалось запустить VLEE движок.';
          _activeEngine = ActiveEngine.none;
          _updateStatus(VpnStatus.disconnected);
          return false;
        }
        // Final CONNECTED arrives via the engine status stream.
        return true;
      }

      _activeEngine = ActiveEngine.v2ray;
      String jsonConfig;

      if (config.hasJsonConfig) {
        // Backend already provided full Xray JSON (with optional balancer +
        // multiple outbounds). Keep its outbounds and routing verbatim, but move
        // the local inbounds onto the ports flutter_v2ray_plus actually wires
        // up — a panel config that ships socks on 10808 / http on 10809 would
        // otherwise leave tun2socks talking to a dead port while colliding with
        // the injected API inbound. See XrayConfigNormalizer.
        jsonConfig = XrayConfigNormalizer.normalizeJsonString(
          config.rawJsonConfig!,
        );
        print('Using raw JSON config, length: ${jsonConfig.length}');
      } else {
        // VLESS-over-TCP links (all REALITY/Vision servers) get the full config
        // built by us in the proven panel shape — the plugin's own URL builder
        // disables sniffing, emits an illegal routing domainStrategy and pollutes
        // the user/REALITY objects, which is why such servers connected but
        // passed no traffic. See VlessLinkConfigBuilder.
        final built = VlessLinkConfigBuilder.build(config);
        if (built != null) {
          jsonConfig = built;
          print(
            'Xray JSON built from link fields, length: ${jsonConfig.length}',
          );
        } else {
          // Non-TCP transports (ws/grpc/xhttp) and Shadowsocks: the plugin's
          // builder still owns the transport tables for these.
          String vlessUrl = config.originalUrl;
          if (vlessUrl.isEmpty) {
            // Fallback: reconstruct from fields
            vlessUrl = _constructVlessLink(config);
          }

          if (vlessUrl.isEmpty) {
            throw Exception("Invalid config: no VLESS URL");
          }

          // Parse VLESS URL to FlutterV2RayURL object
          FlutterV2RayURL v2rayUrl = FlutterV2ray.parseFromURL(vlessUrl);

          // Get full Xray JSON configuration
          jsonConfig = XrayConfigNormalizer.normalizeJsonString(
            v2rayUrl.getFullConfiguration(),
          );

          print('Xray JSON config generated, length: ${jsonConfig.length}');
        }
      }

      // Request VPN permission and start
      if (await _flutterV2ray.requestPermission()) {
        if (attemptId != _connectAttempt) return false;
        // Всегда исключаем само приложение (app.vless.vpn) из TUN-интерфейса:
        // иначе запросы к Firebase Remote Config и API идут через зарубежную
        // ноду, и гео-условие RU в Firebase сбрасывается на не-RU IP.
        final effectiveBlockedApps = <String>{
          'app.vless.vpn',
          ...?blockedApps,
        }.toList();
        print('Split Tunneling: ${effectiveBlockedApps.length} apps blocked from VPN');
        // Retry up to 2 times if foreground service fails to start in time
        for (int attempt = 1; attempt <= 2; attempt++) {
          if (attemptId != _connectAttempt) return false;
          try {
            await _flutterV2ray.startVless(
              remark: config.cleanName,
              config: jsonConfig,
              blockedApps: effectiveBlockedApps,
              proxyOnly: false,
            );
            return attemptId == _connectAttempt;
          } on PlatformException catch (e) {
            if (attemptId != _connectAttempt) return false;
            print('startVless attempt $attempt failed: ${e.code} ${e.message}');
            if (attempt < 2) {
              // Wait long enough for prior foreground service to die before retrying.
              await Future.delayed(const Duration(milliseconds: 1500));
            } else {
              _errorMessage =
                  'Не удалось запустить VPN сервис. Попробуйте ещё раз.';
              _updateStatus(VpnStatus.disconnected);
              return false;
            }
          }
        }
        return false;
      } else {
        if (attemptId != _connectAttempt) return false;
        _errorMessage = "Permission denied";
        _updateStatus(VpnStatus.disconnected);
        return false;
      }
    } catch (e) {
      if (attemptId != _connectAttempt) return false;
      _errorMessage = e.toString();
      print('Connection error: $e');
      _updateStatus(VpnStatus.disconnected);
      return false;
    }
  }

  /// Disconnect from VPN
  Future<bool> disconnect() async {
    _connectAttempt++;
    _allowRestore = false;
    return _disconnectEngine();
  }

  Future<bool> _disconnectEngine() {
    return _pendingStop ??= _stopEngine().whenComplete(
      () => _pendingStop = null,
    );
  }

  Future<bool> _stopEngine() async {
    _stopping = true;
    try {
      _updateStatus(VpnStatus.disconnecting);
      if (_activeEngine == ActiveEngine.awg) {
        await _awgEngine.disconnect();
      } else if (_activeEngine == ActiveEngine.hysteria2) {
        await _hysteriaEngine.disconnect();
      } else if (_activeEngine == ActiveEngine.vlee) {
        await _vleeEngine.disconnect();
      } else if (_activeEngine == ActiveEngine.v2ray) {
        await _flutterV2ray.stopVless();
      }
      _currentConfig = null;
      _activeEngine = ActiveEngine.none;
      _updateStatus(VpnStatus.disconnected);
      return true;
    } catch (e) {
      _errorMessage = e.toString();
      _updateStatus(VpnStatus.error);
      return false;
    } finally {
      _stopping = false;
    }
  }

  /// Helper to reconstruct VLESS link from model
  String _constructVlessLink(VlessConfig config) {
    // Build query parameters manually to avoid double-encoding
    final params = <String, String>{};
    if (config.security.isNotEmpty) params['security'] = config.security;
    if (config.type.isNotEmpty) params['type'] = config.type;
    if (config.flow.isNotEmpty) params['flow'] = config.flow;
    if (config.sni.isNotEmpty) params['sni'] = config.sni;
    if (config.fp.isNotEmpty) params['fp'] = config.fp;
    if (config.pbk.isNotEmpty) params['pbk'] = config.pbk;
    if (config.sid.isNotEmpty) params['sid'] = config.sid;
    if (config.spx.isNotEmpty) params['spx'] = config.spx;

    final queryString = params.entries
        .map((e) => '${e.key}=${e.value}')
        .join('&');

    final fragment = Uri.encodeComponent(config.serverName);

    return 'vless://${config.uuid}@${config.address}:${config.port}?$queryString#$fragment';
  }

  /// Measure server ping/delay
  Future<int?> pingServer(VlessConfig config) async {
    try {
      // Dedicated engines cannot be measured through Xray.
      if (config.isHysteria2 || config.isAwg || config.isVlee) return null;

      // If connected to this server, get connected delay
      if (_status == VpnStatus.connected && _currentConfig?.id == config.id) {
        return await _flutterV2ray.getConnectedServerDelay();
      }

      String jsonConfig;
      if (config.hasJsonConfig) {
        jsonConfig = XrayConfigNormalizer.normalizeJsonString(
          config.rawJsonConfig!,
        );
      } else {
        final built = VlessLinkConfigBuilder.build(config);
        if (built != null) {
          jsonConfig = built;
        } else {
          final vlessUrl = config.originalUrl.isNotEmpty
              ? config.originalUrl
              : _constructVlessLink(config);
          final v2rayUrl = FlutterV2ray.parseFromURL(vlessUrl);
          jsonConfig = XrayConfigNormalizer.normalizeJsonString(
            v2rayUrl.getFullConfiguration(),
          );
        }
      }

      return await _flutterV2ray.getServerDelay(config: jsonConfig);
    } catch (e) {
      print('Ping error for ${config.serverName}: $e');
      return null;
    }
  }

  /// Handle state changes from the dedicated Hysteria2 engine
  void _handleHysteriaStateChange(HysteriaState state) {
    if (_stopping) return;
    if (_allowRestore &&
        _activeEngine == ActiveEngine.none &&
        state == HysteriaState.connected)
      _activeEngine = ActiveEngine.hysteria2;
    if (_activeEngine != ActiveEngine.hysteria2) return;

    switch (state) {
      case HysteriaState.connecting:
        _updateStatus(VpnStatus.connecting);
        break;
      case HysteriaState.connected:
        _updateStatus(VpnStatus.connected);
        break;
      case HysteriaState.stopping:
        _updateStatus(VpnStatus.disconnecting);
        break;
      case HysteriaState.disconnected:
        _activeEngine = ActiveEngine.none;
        _lastHysteriaTraffic = null;
        _lastHysteriaTrafficAt = null;
        _updateStatus(VpnStatus.disconnected);
        break;
      case HysteriaState.error:
        _errorMessage = 'Ошибка Hysteria2 подключения';
        _activeEngine = ActiveEngine.none;
        _updateStatus(VpnStatus.error);
        break;
      case HysteriaState.idle:
        break;
    }
  }

  /// Convert Hysteria2 cumulative traffic totals into the same VlessStatus
  /// events the UI already consumes for speed display.
  void _handleHysteriaTraffic(HysteriaTraffic traffic) {
    if (_activeEngine != ActiveEngine.hysteria2) return;

    final now = DateTime.now();
    int upSpeed = 0;
    int downSpeed = 0;
    final last = _lastHysteriaTraffic;
    final lastAt = _lastHysteriaTrafficAt;
    if (last != null && lastAt != null) {
      final seconds = now.difference(lastAt).inMilliseconds / 1000.0;
      if (seconds > 0) {
        upSpeed = ((traffic.uploadTotal - last.uploadTotal) / seconds)
            .clamp(0, double.maxFinite)
            .round();
        downSpeed = ((traffic.downloadTotal - last.downloadTotal) / seconds)
            .clamp(0, double.maxFinite)
            .round();
      }
    }
    _lastHysteriaTraffic = traffic;
    _lastHysteriaTrafficAt = now;

    _vlessStatusController.add(
      VlessStatus(
        state: 'CONNECTED',
        uploadSpeed: upSpeed,
        downloadSpeed: downSpeed,
        upload: traffic.uploadTotal,
        download: traffic.downloadTotal,
      ),
    );
  }

  /// Handle state changes from the VLEE engine.
  void _handleVleeStateChange(VleeState state) {
    if (_stopping) return;
    if (_allowRestore &&
        _activeEngine == ActiveEngine.none &&
        state == VleeState.connected) {
      _activeEngine = ActiveEngine.vlee;
    }
    if (_activeEngine != ActiveEngine.vlee) return;

    switch (state) {
      case VleeState.connecting:
        _updateStatus(VpnStatus.connecting);
        break;
      case VleeState.connected:
        _updateStatus(VpnStatus.connected);
        break;
      case VleeState.disconnecting:
        _updateStatus(VpnStatus.disconnecting);
        break;
      case VleeState.disconnected:
        _activeEngine = ActiveEngine.none;
        _lastVleeTraffic = null;
        _lastVleeTrafficAt = null;
        _updateStatus(VpnStatus.disconnected);
        break;
      case VleeState.idle:
        break;
    }
  }

  /// Convert VLEE cumulative traffic totals into the same VlessStatus
  /// events the UI already consumes for speed display.
  void _handleVleeTraffic(VleeTraffic traffic) {
    if (_activeEngine != ActiveEngine.vlee) return;

    final now = DateTime.now();
    int upSpeed = 0;
    int downSpeed = 0;
    final last = _lastVleeTraffic;
    final lastAt = _lastVleeTrafficAt;
    if (last != null && lastAt != null) {
      final seconds = now.difference(lastAt).inMilliseconds / 1000.0;
      if (seconds > 0) {
        upSpeed = ((traffic.uploadTotal - last.uploadTotal) / seconds)
            .clamp(0, double.maxFinite)
            .round();
        downSpeed = ((traffic.downloadTotal - last.downloadTotal) / seconds)
            .clamp(0, double.maxFinite)
            .round();
      }
    }
    _lastVleeTraffic = traffic;
    _lastVleeTrafficAt = now;

    _vlessStatusController.add(VlessStatus(
      state: 'CONNECTED',
      uploadSpeed: upSpeed,
      downloadSpeed: downSpeed,
      upload: traffic.uploadTotal,
      download: traffic.downloadTotal,
    ));
  }

  /// Handle status changes from flutter_v2ray_plus
  void _handleStatusChange(VlessStatus vlessStatus) {
    // Broadcast full status for speed/traffic updates
    _vlessStatusController.add(vlessStatus);

    // Map VlessStatus.state to VpnStatus
    String state = vlessStatus.state.toUpperCase();
    if (state == "CONNECTED") {
      _updateStatus(VpnStatus.connected);
    } else if (state == "DISCONNECTED") {
      _updateStatus(VpnStatus.disconnected);
    } else if (state == "CONNECTING") {
      _updateStatus(VpnStatus.connecting);
    } else if (state == "DISCONNECTING") {
      _updateStatus(VpnStatus.disconnecting);
    } else if (state == "ERROR") {
      _updateStatus(VpnStatus.error);
    }
  }

  /// Update status and notify listeners
  void _updateStatus(VpnStatus newStatus) {
    if (_status != newStatus) {
      _status = newStatus;
      _statusController.add(_status);
      onStatusChanged?.call(newStatus);
    }
  }

  /// Clean up resources
  void dispose() {
    _connectAttempt++;
    _statusSubscription?.cancel();
    _hysteriaStatusSubscription?.cancel();
    _hysteriaTrafficSubscription?.cancel();
    _vleeStatusSubscription?.cancel();
    _vleeTrafficSubscription?.cancel();
    _awgStatusSubscription?.cancel();
    _awgEngine.dispose();
    _statusController.close();
    _vlessStatusController.close();
  }
}
