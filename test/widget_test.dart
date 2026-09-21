import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/providers/app_provider.dart';
import 'package:vpn_subscription_app/screens/server_list_screen.dart';
import 'package:vpn_subscription_app/widgets/server_info_card.dart';
import '../tool/server_catalogue_preview.dart';

void main() {
  testWidgets('mixed catalogue labels, locks and manual selection', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final provider = AppProvider(
      initialize: false,
      apiService: CataloguePreviewApi(),
      metricsService: CataloguePreviewMetrics(),
    );
    await tester.runAsync(provider.loadServers);
    await tester.pumpWidget(CataloguePreview(provider: provider));
    expect(provider.selectedServer?.id, 'awg:de');
    await tester.tap(find.byType(ServerInfoCard));
    await tester.pumpAndSettle();
    expect(find.byType(ServerListScreen), findsOneWidget);
    expect(find.text('AWG 3.1'), findsOneWidget);
    expect(find.text('AWG 2.0'), findsNWidgets(2));
    expect(find.text('AWG 3.1 · Premium'), findsOneWidget);
    expect(find.text('VLESS'), findsOneWidget);
    expect(find.byIcon(Icons.lock_outline), findsOneWidget);
    expect(find.byIcon(Icons.cloud_off), findsOneWidget);
    await tester.tap(find.text('Нидерланды • Premium'));
    await tester.pump();
    expect(provider.selectedServer?.id, 'awg:de');
    expect(find.text('Для этого сервера нужен Premium'), findsOneWidget);
    await tester.tap(find.text('Нидерланды • Амстердам'));
    await tester.pumpAndSettle();
    expect(provider.selectedServer?.id, 'awg:nl');
    expect(provider.isAutoSelect, isFalse);
    expect(find.byType(ServerListScreen), findsNothing);
    expect(find.textContaining('AWG 2.0'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    provider.dispose();
  });
}
