import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:provider/provider.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/providers/app_provider.dart';
import 'package:vpn_subscription_app/services/api_service.dart';
import 'package:vpn_subscription_app/services/metrics_service.dart';
import 'package:vpn_subscription_app/widgets/server_info_card.dart';

// Deliberately no credentials, Firebase, ads or native VPN calls in this UI fixture.
class CataloguePreviewApi extends ApiService {
  static VlessConfig awg(
    String id,
    String name,
    String version, {
    bool locked = false,
    bool available = true,
    bool recommend = false,
  }) => VlessConfig(
    id: id,
    serverName: name,
    country: name,
    address: '127.0.0.1',
    port: 51820,
    uuid: '',
    protocol: 'awg',
    awgVersion: version,
    awgConfig: available ? 'fixture-not-a-real-key' : '',
    flag: id == 'awg:de' ? '🇩🇪' : '🇳🇱',
    premium: locked,
    locked: locked,
    available: available,
    recommend: recommend,
  );

  final nodes = [
    awg('awg:de', 'Германия • Берлин', '3.1', recommend: true),
    awg('awg:nl', 'Нидерланды • Амстердам', '2.0'),
    awg(
      'awg:premium',
      'Нидерланды • Premium',
      '3.1',
      locked: true,
      available: false,
    ),
    awg('awg:offline', 'Нидерланды • Maintenance', '2.0', available: false),
    VlessConfig(
      id: 'vless:fi',
      serverName: 'Финляндия',
      country: 'Финляндия',
      flag: '🇫🇮',
      address: '127.0.0.1',
      port: 1,
      uuid: 'test',
    ),
  ];

  @override
  Future<List<VlessConfig>> fetchServers() async => nodes;
  @override
  Future<List<VlessConfig>> fetchAwgConfigs() async =>
      nodes.where((s) => s.isAwg).toList();
}

class CataloguePreviewMetrics extends MetricsService {
  @override
  Future<Map<String, int>> fetchMetrics() async => {};
}

class CataloguePreview extends StatelessWidget {
  const CataloguePreview({super.key, required this.provider});
  final AppProvider provider;

  @override
  Widget build(BuildContext context) => ChangeNotifierProvider.value(
    value: provider,
    child: MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      locale: const Locale('ru'),
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [Locale('ru'), Locale('en')],
      home: Scaffold(
        appBar: AppBar(title: const Text('VLESS VPN · Серверы')),
        body: const Padding(
          padding: EdgeInsets.all(20),
          child: Column(
            children: [
              Text('UI-проверка: тестовые серверы, VPN не запускается'),
              SizedBox(height: 24),
              ServerInfoCard(),
            ],
          ),
        ),
      ),
    ),
  );
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final provider = AppProvider(
    initialize: false,
    apiService: CataloguePreviewApi(),
    metricsService: CataloguePreviewMetrics(),
  );
  await provider.loadServers();
  runApp(CataloguePreview(provider: provider));
}
