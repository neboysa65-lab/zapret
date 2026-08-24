import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_v2ray_plus/flutter_v2ray.dart';
import 'package:flutter_v2ray_plus/url/url.dart';
import '../models/vless_config_model.dart';
import 'hysteria_engine_service.dart';
import 'vless_link_config_builder.dart';
import 'xray_config_normalizer.dart';

/// VPN connection status
enum VpnStatus { disconnected, connecting, connected, disconnecting, error }

/// Which engine currently owns the tunnel.
enum ActiveEngine { none, v2ray, hysteria2 }

/// Service for VPN connection management.
///
/// VLESS / Shadowsocks / raw JSON configs go through flutter_v2ray_plus
/// (unchanged). Hysteria2 servers are routed to the dedicated native engine
/// (see HysteriaEngineService), ported from the gwapk project.
class VpnService {
  late FlutterV2ray _flutterV2ray;
  final HysteriaEngineService _hysteriaEngine = HysteriaEngineService();
  ActiveEngine _activeEngine = ActiveEngine.none;
  VpnStatus _status = VpnStatus.disconnected;
  VlessConfig? _currentConfig;
  String? _errorMessage;
  StreamSubscription<VlessStatus>? _statusSubscription;
  StreamSubscription<HysteriaState>? _hysteriaStatusSubscription;
  StreamSubscription<HysteriaTraffic>? _hysteriaTrafficSubscription;
  HysteriaTraffic? _lastHysteriaTraffic;
  DateTime? _lastHysteriaTrafficAt;

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

  VpnService() {
    _flutterV2ray = FlutterV2ray();
  }

  /// Initialize VPN engine
  Future<bool> initialize() async {
    try {
      await _flutterV2ray.initializeVless(
        notificationIconResourceType: "mipmap",
        notificationIconResourceName: "ic_launcher",
      );

      // Listen to the status stream from flutter_v2ray_plus
      _statusSubscription = _flutterV2ray.onStatusChanged.listen((vlessStatus) {
        // Ignore v2ray engine chatter while the Hysteria2 engine owns the tunnel.
        if (_activeEngine == ActiveEngine.hysteria2) return;
        _handleStatusChange(vlessStatus);
      });

      // Dedicated Hysteria2 engine: init + state / traffic streams.
      await _hysteriaEngine.initialize();
      _hysteriaStatusSubscription =
          _hysteriaEngine.statusStream.listen(_handleHysteriaStateChange);
      _hysteriaTrafficSubscription =
          _hysteriaEngine.trafficStream.listen(_handleHysteriaTraffic);

      return true;
    } catch (e) {
      _errorMessage = 'Initialization error: $e';
      return false;
    }
  }

  /// Connect to VPN
  /// [blockedApps] — optional list of package names to exclude from VPN tunnel (split tunneling)
  Future<bool> connect(VlessConfig config, {List<String>? blockedApps}) async {
    try {
      if (_status == VpnStatus.connected || _status == VpnStatus.connecting) {
        await disconnect();
        // Give the OS time to fully tear down the previous foreground service
        // before starting a new one. Otherwise Android 12+ throws
        // ForegroundServiceDidNotStartInTimeException.
        await Future.delayed(const Duration(milliseconds: 600));
      }

      _updateStatus(VpnStatus.connecting);
      _errorMessage = null;
      _currentConfig = config;

      // --- Hysteria2: handled by the dedicated ported engine, never by
      // flutter_v2ray_plus (its stock Xray core has no hysteria outbound). ---
      if (config.isHysteria2) {
        _activeEngine = ActiveEngine.hysteria2;
        final url = config.originalUrl.isNotEmpty &&
                (config.originalUrl.startsWith('hysteria2://') ||
                    config.originalUrl.startsWith('hy2://'))
            ? config.originalUrl
            : config.hysteria2Url;
        print('Hysteria2 URL: $url');

        final started = await _hysteriaEngine.connect(url);
        if (!started) {
          _errorMessage = 'Не удалось запустить Hysteria2 движок.';
          _activeEngine = ActiveEngine.none;
          _updateStatus(VpnStatus.disconnected);
          return false;
        }
        // Final CONNECTED/ERROR arrives via the engine status stream.
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
        jsonConfig =
            XrayConfigNormalizer.normalizeJsonString(config.rawJsonConfig!);
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
          print('Xray JSON built from link fields, length: ${jsonConfig.length}');
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

          print('VLESS URL: $vlessUrl');

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
        if (blockedApps != null && blockedApps.isNotEmpty) {
          print('Split Tunneling: ${blockedApps.length} apps blocked from VPN');
        }
        // Retry up to 2 times if foreground service fails to start in time
        for (int attempt = 1; attempt <= 2; attempt++) {
          try {
            await _flutterV2ray.startVless(
              remark: config.cleanName,
              config: jsonConfig,
              blockedApps: blockedApps,
              proxyOnly: false,
            );
            return true;
          } on PlatformException catch (e) {
            print('startVless attempt $attempt failed: ${e.code} ${e.message}');
            if (attempt < 2) {
              // Wait long enough for prior foreground service to die before retrying.
              await Future.delayed(const Duration(milliseconds: 1500));
            } else {
              _errorMessage = 'Не удалось запустить VPN сервис. Попробуйте ещё раз.';
              _updateStatus(VpnStatus.disconnected);
              return false;
            }
          }
        }
        return false;
      } else {
        _errorMessage = "Permission denied";
        _updateStatus(VpnStatus.disconnected);
        return false;
      }
    } catch (e) {
      _errorMessage = e.toString();
      print('Connection error: $e');
      _updateStatus(VpnStatus.disconnected);
      return false;
    }
  }

  /// Disconnect from VPN
  Future<bool> disconnect() async {
    try {
      _updateStatus(VpnStatus.disconnecting);
      if (_activeEngine == ActiveEngine.hysteria2) {
        await _hysteriaEngine.disconnect();
      } else {
        await _flutterV2ray.stopVless();
      }
      _currentConfig = null;
      // Status will be updated via the engine status stream
      return true;
    } catch (e) {
      _errorMessage = e.toString();
      _activeEngine = ActiveEngine.none;
      _updateStatus(VpnStatus.disconnected);
      return false;
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

    final queryString =
        params.entries.map((e) => '${e.key}=${e.value}').join('&');

    final fragment = Uri.encodeComponent(config.serverName);

    return 'vless://${config.uuid}@${config.address}:${config.port}?$queryString#$fragment';
  }

  /// Measure server ping/delay
  Future<int?> pingServer(VlessConfig config) async {
    try {
      // Hysteria2 servers are not measurable through flutter_v2ray_plus
      // (QUIC/UDP-based). The server list already measures them with a raw
      // TCP socket in AppProvider.loadServers().
      if (config.isHysteria2) return null;

      // If connected to this server, get connected delay
      if (_status == VpnStatus.connected && _currentConfig?.id == config.id) {
        return await _flutterV2ray.getConnectedServerDelay();
      }

      String jsonConfig;
      if (config.hasJsonConfig) {
        jsonConfig =
            XrayConfigNormalizer.normalizeJsonString(config.rawJsonConfig!);
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
    _statusSubscription?.cancel();
    _hysteriaStatusSubscription?.cancel();
    _hysteriaTrafficSubscription?.cancel();
    _statusController.close();
    _vlessStatusController.close();
  }
}
