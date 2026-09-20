import 'package:flutter/foundation.dart';
import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import '../models/vless_config_model.dart';
import '../services/vpn_service.dart';
import '../services/api_service.dart';
import '../services/metrics_service.dart';
import '../services/split_tunnel_service.dart';
import '../services/rating_service.dart';

enum ServerLoadTier { low, medium, high }

class AppProvider with ChangeNotifier {
  final VpnService _vpnService;
  final ApiService _apiService;
  final MetricsService _metricsService;
  int _loadGeneration = 0;
  String? _account;
  bool _premium = false;
  bool _disposed = false;
  int _connectGeneration = 0;
  bool _preparingConnection = false;
  StreamSubscription<dynamic>? _trafficSubscription;
  StreamSubscription<VpnStatus>? _vpnSubscription;

  // VPN state
  VpnStatus _vpnStatus = VpnStatus.disconnected;
  List<VlessConfig> _servers = [];
  Map<String, int> _serverMetrics =
      {}; // Normalized nodeName to usersOnline map
  int _totalOnlineUsers = 0; // Total users across all matched servers

  VlessConfig? _selectedServer;
  bool _isAutoSelect = true; // Default to auto
  String? _errorMessage;
  bool _isLoading = false;

  // Statistics
  Duration _connectionDuration = Duration.zero;
  Timer? _timer;
  String _downloadSpeed = "0.0 B/s";
  String _uploadSpeed = "0.0 B/s";
  int _downloadSpeedBytes = 0;
  int _uploadSpeedBytes = 0;

  // Getters
  VpnStatus get vpnStatus => _vpnStatus;
  List<VlessConfig> get servers => _servers;
  VlessConfig? get selectedServer => _selectedServer;
  bool get isAutoSelect => _isAutoSelect;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  Duration get connectionDuration => _connectionDuration;
  String get downloadSpeed => _downloadSpeed;
  String get uploadSpeed => _uploadSpeed;
  int get downloadSpeedBytes => _downloadSpeedBytes;
  int get uploadSpeedBytes => _uploadSpeedBytes;

  // New Metrics Getters
  int get totalOnlineUsers => _totalOnlineUsers;

  int getServerOnlineUsers(VlessConfig config) {
    if (config.isAwg) return 0;
    return _serverMetrics[_normalizeServerName(config.serverName)] ??
        _serverMetrics[_normalizeAddress(config.address)] ??
        0;
  }

  /// Calculates a logical percentage of server load.
  /// Возвращает процент загруженности как математическое сравнение серверов между собой.
  double getServerLoadPercentage(VlessConfig config) {
    if (_serverMetrics.isEmpty) return 0.0;

    int currentOnline = getServerOnlineUsers(config);
    if (currentOnline == 0) return 0.0;

    // 1. Ищем самый загруженный сервер среди всех доступных
    int maxOnline = 1;
    for (var s in _servers) {
      final sOnline = getServerMetricsByConfig(s);
      if (sOnline > maxOnline) maxOnline = sOnline;
    }

    // 2. Сравниваем текущий сервер с самым загруженным!
    // Если на самом загруженном сейчас 30 человек, то он будет считаться за 100%
    // А сервер с 15 людьми автоматически станет заполнен на 50%.
    // Чтобы пустые сервера (с 1 юзером) не горели на 100%, мы ставим минимальный порог для 100% загрузки = 10 человек.
    int comparativeCapacity = math.max(10, maxOnline);

    double percentage = (currentOnline / comparativeCapacity) * 100;
    return percentage.clamp(0.0, 100.0);
  }

  int getServerMetricsByConfig(VlessConfig config) {
    if (config.isAwg) return 0;
    return _serverMetrics[_normalizeServerName(config.serverName)] ??
        _serverMetrics[_normalizeAddress(config.address)] ??
        0;
  }

  /// Возвращает тир нагрузки сервера: low / medium / high
  ServerLoadTier getServerLoadTier(VlessConfig config) {
    final load = getServerLoadPercentage(config);
    if (load < 35) return ServerLoadTier.low;
    if (load < 70) return ServerLoadTier.medium;
    return ServerLoadTier.high;
  }

  int _tierIndex(ServerLoadTier tier) {
    switch (tier) {
      case ServerLoadTier.low:
        return 0;
      case ServerLoadTier.medium:
        return 1;
      case ServerLoadTier.high:
        return 2;
    }
  }

  /// Normalizes string to match nodes. E.g., 'VLESSAPP \u{1F1F7}\u{1F1FA} RU 1' and 'VLESSAPP-RU01'
  String _normalizeServerName(String name) {
    // 1. Убираем всё, кроме букв и цифр, переводим в нижний регистр
    String cleaned = name.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toLowerCase();

    // 2. Убираем лишнее слово "app", так как иногда в подписке есть vlessapp, а в API просто vless
    cleaned = cleaned.replaceAll('app', '');
    cleaned = cleaned.replaceAll('vless', '');

    // 3. Вырезаем все ведущие нули у цифр на конце строки (чтобы '01' и '1' стали просто '1')
    cleaned = cleaned.replaceAllMapped(
      RegExp(r'0+([1-9]+)$'),
      (match) => match.group(1)!,
    );

    // В итоге 'VLESSAPP RU 01' превратится в 'ru1',
    // и 'VLESSAPP-RU01' превратится в 'ru1'. Это гарантирует 100% матчинг.
    return cleaned;
  }

  /// Normalizes server address to match metric keys.
  /// E.g., 'vleesvpn-app-ru01.gw-vpn.click' → 'ru1'
  String _normalizeAddress(String address) {
    final host = address.split('.').first;
    String cleaned = host.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toLowerCase();
    cleaned = cleaned.replaceAll('app', '');
    cleaned = cleaned.replaceAll('vless', '');
    cleaned = cleaned.replaceAll('vpn', '');
    cleaned = cleaned.replaceAllMapped(
      RegExp(r'0+([1-9]+)$'),
      (match) => match.group(1)!,
    );
    return cleaned;
  }

  AppProvider({
    VpnService? vpnService,
    ApiService? apiService,
    MetricsService? metricsService,
    bool initialize = true,
  }) : _vpnService = vpnService ?? VpnService(),
       _apiService = apiService ?? ApiService(),
       _metricsService = metricsService ?? MetricsService() {
    _listenVpn();
    if (initialize) _initialize();
  }

  void updateAccount(String? userId, bool premium) {
    if (_account == userId && _premium == premium) return;
    final changedUser = _account != userId;
    _account = userId;
    _premium = premium;
    _loadGeneration++;
    _connectGeneration++;
    if (changedUser) {
      _servers = [];
      _selectedServer = null;
      _isAutoSelect = true;
    }
    Future.microtask(() async {
      if (_disposed) return;
      if (changedUser && _vpnStatus != VpnStatus.disconnected) {
        await _vpnService.disconnect();
      }
      if (!_disposed && userId != null && _account == userId) {
        await loadServers();
      }
      if (!_disposed) notifyListeners();
    });
  }

  void _listenVpn() {
    // Listen to VlessStatus stream for speed/traffic updates
    _trafficSubscription = _vpnService.vlessStatusStream.listen((vlessStatus) {
      _downloadSpeedBytes = vlessStatus.downloadSpeed;
      _uploadSpeedBytes = vlessStatus.uploadSpeed;
      _downloadSpeed = _formatBytes(vlessStatus.downloadSpeed);
      _uploadSpeed = _formatBytes(vlessStatus.uploadSpeed);

      notifyListeners();
    });

    // Listen to VPN status
    _vpnSubscription = _vpnService.statusStream.listen((status) {
      if (_preparingConnection && status == VpnStatus.disconnected) return;
      _vpnStatus = status;

      if (status == VpnStatus.connected) {
        _startTimer();
      } else if (status == VpnStatus.disconnected) {
        _stopTimer();
        _connectionDuration = Duration.zero;
        _downloadSpeed = "0.0 B/s";
        _uploadSpeed = "0.0 B/s";
        _downloadSpeedBytes = 0;
        _uploadSpeedBytes = 0;
        RatingService().maybeShowReview();
      } else if (status == VpnStatus.error) {
        _stopTimer();
        _errorMessage = _vpnService.errorMessage;
      }

      notifyListeners();
    });
  }

  Future<void> _initialize() async {
    _isLoading = true;
    notifyListeners();
    // Subscribe before initialization: a native tunnel can already be running.
    await _vpnService.initialize();
    if (_disposed) return;
    await loadServers();
    if (_disposed) return;

    _isLoading = false;
    notifyListeners();
  }

  /// Load servers from API
  Future<void> loadServers() async {
    if (_disposed) return;
    final generation = ++_loadGeneration;
    try {
      _isLoading = true;
      notifyListeners();

      // Fetch servers and metrics concurrently
      final futures = await Future.wait([
        _apiService.fetchServers(),
        _metricsService.fetchMetrics(),
      ]);

      // Last line of defence: the list highlights its selection by comparing
      // ids, so a repeated id would render one tap as two selected rows.
      // ApiService already dedupes, but a cached or hand-imported list must not
      // be able to reintroduce the problem.
      final servers = VlessConfig.dedupe(futures[0] as List<VlessConfig>);
      final rawMetrics = futures[1] as Map<String, int>;
      if (generation != _loadGeneration) return;

      // Normalize metric keys for robust matching
      _serverMetrics.clear();
      _totalOnlineUsers = 0;
      rawMetrics.forEach((nodeName, online) {
        // Засчитываем пользователей ТОЛЬКО если оригинальное имя ноды начинается с "VLESSAPP-"
        if (nodeName.toUpperCase().startsWith('VLESSAPP-')) {
          String normalizedNode = _normalizeServerName(nodeName);
          _serverMetrics[normalizedNode] =
              (_serverMetrics[normalizedNode] ?? 0) + online;
          _totalOnlineUsers += online;
        }
      });

      // Measure ping for all servers concurrently
      final pingedServers = await Future.wait(
        servers.map((server) async {
          // AWG endpoints speak UDP: a failed TCP connect says nothing about them.
          if (server.isAwg || !server.canConnect) return server;
          try {
            final stopwatch = Stopwatch()..start();
            // Ограничение в 2.5 секунды (2500 миллисекунд)
            final socket = await Socket.connect(
              server.address,
              server.port,
              timeout: const Duration(milliseconds: 2500),
            );
            socket.destroy();
            stopwatch.stop();
            return server.copyWith(ping: stopwatch.elapsedMilliseconds);
          } catch (e) {
            return server.copyWith(ping: 0); // 0 means failed/timeout
          }
        }),
      );

      // Sort: green (low) → yellow (medium) → red (high),
      // within same tier — by ping ascending (0 = timeout, goes last)
      if (generation != _loadGeneration) return;
      _servers = List.from(pingedServers)..sort(_compareServers);
      final selected = _servers.where(
        (s) => s.id == _selectedServer?.id && s.canConnect,
      );
      _selectedServer = selected.isEmpty ? null : selected.first;

      notifyListeners(); // Show list immediately

      // If auto-select is on, or no server selected, pick best
      if ((_isAutoSelect && _vpnStatus == VpnStatus.disconnected) ||
          _selectedServer == null) {
        await _autoSelectBestServer();
      }
    } catch (e) {
      if (generation != _loadGeneration) return;
      _errorMessage = 'Failed to load servers: $e';
    } finally {
      if (generation == _loadGeneration) {
        _isLoading = false;
        notifyListeners();
      }
    }
  }

  /// Refresh only metrics (without re-pinging servers) — for periodic updates
  Future<void> refreshMetrics() async {
    if (_servers.isEmpty) return;
    try {
      final rawMetrics = await _metricsService.fetchMetrics();
      if (_disposed) return;
      _serverMetrics.clear();
      _totalOnlineUsers = 0;
      rawMetrics.forEach((nodeName, speed) {
        if (nodeName.toUpperCase().startsWith('VLESSAPP-')) {
          final key = _normalizeServerName(nodeName);
          _serverMetrics[key] = (_serverMetrics[key] ?? 0) + speed;
          _totalOnlineUsers += speed;
        }
      });
      // Re-sort by tier + ping
      _servers = List.from(_servers)..sort(_compareServers);
      if (_isAutoSelect && _vpnStatus == VpnStatus.disconnected) {
        await _autoSelectBestServer();
      }
      notifyListeners();
    } catch (_) {}
  }

  /// Automatically select the best server:
  /// 1st priority — green (low load), 2nd — yellow, 3rd — red.
  /// Within the same tier, pick the lowest ping.
  Future<void> _autoSelectBestServer() async {
    final pool = _servers.where((s) => s.canConnect).toList()
      ..sort(_compareServers);
    _selectedServer = pool.isEmpty ? null : pool.first;
    _isAutoSelect = true;
    notifyListeners();
  }

  /// Select a specific server (disables auto)
  /// If currently connected, auto-reconnects to the new server
  void selectServer(VlessConfig server) {
    if (!server.canConnect || _preparingConnection) return;
    final wasConnected =
        _vpnStatus == VpnStatus.connected || _vpnStatus == VpnStatus.connecting;
    final isSameServer = _selectedServer?.id == server.id;

    _selectedServer = server;
    _isAutoSelect = false;
    notifyListeners();

    // Auto-reconnect if connected and server changed
    if (wasConnected && !isSameServer) {
      _reconnect();
    }
  }

  /// Toggle Auto Selection
  /// If currently connected, auto-reconnects to the best server
  void toggleAutoSelect() {
    if (_preparingConnection) return;
    final wasConnected =
        _vpnStatus == VpnStatus.connected || _vpnStatus == VpnStatus.connecting;
    final previousServer = _selectedServer;

    _isAutoSelect = true;
    _autoSelectBestServer().then((_) {
      // Auto-reconnect if connected and server changed
      if (wasConnected && _selectedServer?.id != previousServer?.id) {
        _reconnect();
      }
    });
  }

  /// Disconnect from current server and reconnect to selected one
  Future<void> _reconnect() async {
    final generation = ++_connectGeneration;
    _preparingConnection = true;
    _vpnStatus = VpnStatus.connecting;
    notifyListeners();
    final stopped = await _vpnService.disconnect();
    // Small delay to ensure clean disconnect
    await Future.delayed(const Duration(milliseconds: 500));
    if (_disposed || generation != _connectGeneration) return;
    _preparingConnection = false;
    if (!stopped) {
      _vpnStatus = VpnStatus.error;
      _errorMessage = _vpnService.errorMessage;
      notifyListeners();
      return;
    }
    _vpnStatus = VpnStatus.disconnected;
    await connectVpn();
  }

  /// Connect to VPN
  Future<void> connectVpn({Future<void> Function()? beforeConnect}) async {
    if (_disposed ||
        _vpnStatus == VpnStatus.connecting ||
        _vpnStatus == VpnStatus.disconnecting ||
        _vpnStatus == VpnStatus.connected)
      return;
    final connectGeneration = ++_connectGeneration;
    _preparingConnection = true;
    _vpnStatus = VpnStatus.connecting;
    _errorMessage = null;
    notifyListeners();
    try {
      await beforeConnect?.call();
      if (_disposed || connectGeneration != _connectGeneration) return;
      print(
        '🔌 connectVpn() called, servers: ${_servers.length}, selectedServer: ${_selectedServer?.serverName}',
      );

      if (_servers.isEmpty) {
        await loadServers();
        if (_disposed || connectGeneration != _connectGeneration) return;
        if (_servers.isEmpty) {
          _vpnStatus = VpnStatus.disconnected;
          _errorMessage = "Серверы не найдены";
          notifyListeners();
          return;
        }
      }

      VlessConfig? target = _selectedServer;

      if (_isAutoSelect) {
        if (target == null) await _autoSelectBestServer();
        target = _selectedServer;
      }

      if (target == null) {
        _vpnStatus = VpnStatus.disconnected;
        _errorMessage = "Сервер не выбран";
        notifyListeners();
        return;
      }

      if (target.isAwg) {
        _vpnStatus = VpnStatus.connecting;
        _errorMessage = null;
        notifyListeners();
        try {
          final fresh = await _apiService.fetchAwgConfigs();
          if (_disposed || connectGeneration != _connectGeneration) {
            return;
          }
          final matches = fresh.where(
            (s) => s.id == target!.id && s.canConnect,
          );
          if (matches.isEmpty) {
            throw StateError('AWG-сервер недоступен или нужен Premium');
          }
          target = matches.first;
          _selectedServer = target;
        } catch (_) {
          if (_disposed || connectGeneration != _connectGeneration) return;
          _vpnStatus = VpnStatus.disconnected;
          _errorMessage =
              'Не удалось получить актуальный AWG-конфиг. Обновите список серверов.';
          notifyListeners();
          return;
        }
      }

      print(
        '🎯 Connecting to: ${target.serverName} (${target.address}:${target.port})',
      );

      // Получаем blockedApps для Split Tunneling
      List<String>? blockedApps;
      try {
        blockedApps = await SplitTunnelService.getBlockedApps();
        if (blockedApps != null) {
          print(
            '🔀 Split Tunneling active: ${blockedApps.length} apps excluded from VPN',
          );
        }
      } catch (e) {
        print('⚠️ Split tunnel error: $e');
      }

      if (_disposed || connectGeneration != _connectGeneration) return;
      _preparingConnection = false;
      final success = await _vpnService.connect(
        target,
        blockedApps: blockedApps,
      );
      if (_disposed || connectGeneration != _connectGeneration) return;
      if (!success) {
        _vpnStatus = _vpnService.status;
        _errorMessage = _vpnService.errorMessage;
        print('❌ Connection failed: $_errorMessage');
        notifyListeners();
      } else {
        print('✅ Connection initiated successfully');
      }
    } catch (_) {
      if (_disposed || connectGeneration != _connectGeneration) return;
      _vpnStatus = VpnStatus.disconnected;
      _errorMessage = 'Не удалось подготовить подключение. Попробуйте ещё раз.';
      notifyListeners();
    } finally {
      if (connectGeneration == _connectGeneration) _preparingConnection = false;
    }
  }

  /// Disconnect VPN
  Future<void> disconnectVpn() async {
    final generation = ++_connectGeneration;
    _preparingConnection = false;
    _vpnStatus = VpnStatus.disconnecting;
    _errorMessage = null;
    notifyListeners();
    final stopped = await _vpnService.disconnect();
    if (_disposed || generation != _connectGeneration) return;
    _vpnStatus = stopped ? VpnStatus.disconnected : VpnStatus.error;
    if (!stopped) _errorMessage = _vpnService.errorMessage;
    notifyListeners();
  }

  /// Clear error message
  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  int _compareServers(VlessConfig a, VlessConfig b) {
    if (a.canConnect != b.canConnect) return a.canConnect ? -1 : 1;
    if (a.recommend != b.recommend) return a.recommend ? -1 : 1;
    if (a.priority != b.priority) return b.priority.compareTo(a.priority);
    final tier = _tierIndex(
      getServerLoadTier(a),
    ).compareTo(_tierIndex(getServerLoadTier(b)));
    if (tier != 0) return tier;
    if (a.ping == 0 && b.ping == 0) return a.id.compareTo(b.id);
    if (a.ping == 0) return 1;
    if (b.ping == 0) return -1;
    return a.ping.compareTo(b.ping);
  }

  // Timer Logic
  void _startTimer() {
    _stopTimer();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      _connectionDuration += const Duration(seconds: 1);
      notifyListeners();
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  /// Format bytes to readable string
  String _formatBytes(int bytes) {
    if (bytes <= 0) return "0.0 B/s";
    const suffixes = ["B/s", "KB/s", "MB/s", "GB/s"];
    var i = (math.log(bytes) / math.log(1024)).floor();
    return '${(bytes / math.pow(1024, i)).toStringAsFixed(1)} ${suffixes[i]}';
  }

  @override
  void dispose() {
    _disposed = true;
    _loadGeneration++;
    _connectGeneration++;
    _preparingConnection = false;
    _trafficSubscription?.cancel();
    _vpnSubscription?.cancel();
    _apiService.dispose();
    _vpnService.dispose();
    _stopTimer();
    super.dispose();
  }
}
