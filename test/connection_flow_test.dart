import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/providers/app_provider.dart';
import 'package:vpn_subscription_app/providers/auth_provider.dart';
import 'package:vpn_subscription_app/screens/home_screen.dart';
import 'package:vpn_subscription_app/services/api_service.dart';
import 'package:vpn_subscription_app/services/vpn_service.dart';
import 'package:vpn_subscription_app/widgets/power_button.dart';
import '../tool/server_catalogue_preview.dart';

class FlowApi extends ApiService {
  FlowApi(this.node);
  final VlessConfig node;
  Completer<List<VlessConfig>>? provisioning;
  @override
  Future<List<VlessConfig>> fetchServers() async => [node];
  @override
  Future<List<VlessConfig>> fetchAwgConfigs() async =>
      provisioning == null ? [node] : provisioning!.future;
}

class FlowVpn extends VpnService {
  final events = StreamController<VpnStatus>.broadcast();
  final started = <String>[];
  VpnStatus state = VpnStatus.disconnected;
  @override
  VpnStatus get status => state;
  @override
  Stream<VpnStatus> get statusStream => events.stream;
  @override
  Future<bool> connect(VlessConfig config, {List<String>? blockedApps}) async {
    started.add(config.protocol);
    events.add(state = VpnStatus.connecting);
    return true;
  }

  @override
  Future<bool> disconnect() async {
    events.add(state = VpnStatus.disconnected);
    return true;
  }

  @override
  void dispose() {
    events.close();
    super.dispose();
  }
}

class FlowAuth extends AuthProvider {
  FlowAuth(this.premium);
  final bool premium;
  @override
  bool get isPremium => premium;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  for (final protocol in ['vless', 'ss', 'hysteria2', 'awg2', 'awg3']) {
    for (final premium in [false, true]) {
      testWidgets(
        '$protocol: ${premium ? 'Premium skips' : 'free waits for'} ad, second tap cancels',
        (tester) async {
          tester.view.physicalSize = const Size(412, 915);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final node = VlessConfig(
            id: protocol,
            serverName: protocol,
            address: '127.0.0.1',
            port: 1,
            uuid: 'fixture',
            protocol: protocol.startsWith('awg') ? 'awg' : protocol,
            awgVersion: protocol == 'awg2' ? '2.0' : '3.1',
            awgConfig: 'fixture',
          );
          final vpn = FlowVpn();
          final api = FlowApi(node);
          final provider = AppProvider(
            initialize: false,
            vpnService: vpn,
            apiService: api,
            metricsService: CataloguePreviewMetrics(),
          );
          await tester.runAsync(provider.loadServers);
          final ad = Completer<bool>();
          var shown = 0;
          await tester.pumpWidget(
            MultiProvider(
              providers: [
                ChangeNotifierProvider.value(value: provider),
                ChangeNotifierProvider<AuthProvider>(
                  create: (_) => FlowAuth(premium),
                ),
              ],
              child: MaterialApp(
                locale: const Locale('ru'),
                localizationsDelegates: const [
                  AppLocalizations.delegate,
                  GlobalMaterialLocalizations.delegate,
                  GlobalWidgetsLocalizations.delegate,
                  GlobalCupertinoLocalizations.delegate,
                ],
                supportedLocales: const [Locale('ru')],
                home: HomeScreen(
                  loadAd: () async {},
                  showAd: () {
                    shown++;
                    return ad.future;
                  },
                ),
              ),
            ),
          );
          await tester.tap(find.byType(PowerButton));
          await tester.pump();
          expect(provider.vpnStatus, VpnStatus.connecting);
          expect(shown, premium ? 0 : 1);
          if (!premium) {
            expect(vpn.started, isEmpty);
            ad.complete(true);
            await tester.pump();
          }
          expect(vpn.started, [node.protocol]);
          expect(find.byIcon(Icons.close_rounded), findsOneWidget);
          await tester.tap(find.byType(PowerButton));
          await tester.pump();
          expect(provider.vpnStatus, VpnStatus.disconnected);
          expect(shown, premium ? 0 : 1);
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
          provider.dispose();
        },
      );
    }
  }

  test(
    'cancellation during ad blocks late dismissal and duplicate requests',
    () async {
      final vpn = FlowVpn();
      final provider = AppProvider(
        initialize: false,
        vpnService: vpn,
        apiService: CataloguePreviewApi(),
        metricsService: CataloguePreviewMetrics(),
      );
      await provider.loadServers();
      final ad = Completer<void>();
      var shown = 0;
      final connecting = provider.connectVpn(
        beforeConnect: () {
          shown++;
          return ad.future;
        },
      );
      await provider.connectVpn(
        beforeConnect: () async {
          shown++;
        },
      );
      expect(shown, 1);
      await provider.disconnectVpn();
      ad.complete();
      await connecting;
      expect(provider.vpnStatus, VpnStatus.disconnected);
      expect(vpn.started, isEmpty);
      provider.dispose();
    },
  );

  test(
    'cancellation during AWG provisioning blocks late API response',
    () async {
      final vpn = FlowVpn();
      final api = FlowApi(CataloguePreviewApi.awg('awg:test', 'AWG', '3.1'));
      final provider = AppProvider(
        initialize: false,
        vpnService: vpn,
        apiService: api,
        metricsService: CataloguePreviewMetrics(),
      );
      await provider.loadServers();
      api.provisioning = Completer<List<VlessConfig>>();
      final connecting = provider.connectVpn();
      await Future<void>.delayed(Duration.zero);
      await provider.disconnectVpn();
      api.provisioning!.complete([api.node]);
      await connecting;
      expect(vpn.started, isEmpty);
      expect(provider.vpnStatus, VpnStatus.disconnected);
      provider.dispose();
    },
  );
}
