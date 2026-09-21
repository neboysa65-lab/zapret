import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/providers/app_provider.dart';
import '../tool/server_catalogue_preview.dart';

void main() {
  test(
    'AWG auto selection ignores locked/unavailable nodes and stale selection',
    () async {
      final api = CataloguePreviewApi();
      api.nodes.removeWhere((s) => !s.isAwg);
      final provider = AppProvider(
        initialize: false,
        apiService: api,
        metricsService: CataloguePreviewMetrics(),
      );
      await provider.loadServers();
      expect(provider.selectedServer?.id, 'awg:de');
      provider.selectServer(api.nodes.firstWhere((s) => s.locked));
      expect(provider.selectedServer?.id, 'awg:de');
      api.nodes.removeWhere((s) => s.id == 'awg:de');
      await provider.loadServers();
      expect(provider.selectedServer?.id, 'awg:nl');
      api.nodes.removeWhere((s) => s.canConnect);
      await provider.loadServers();
      expect(provider.selectedServer, isNull);
      provider.dispose();
    },
  );
}
