import 'dart:io';

import 'mimic_transport_service.dart';

/// Направляет dart:io HTTP-клиенты (top-level http.get/post, IOClient)
/// через локальный MTP-прокси, когда туннель жив. Ставится в main()
/// до первых сетевых запросов.
class MimicHttpOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? context) {
    final client = super.createHttpClient(context);
    client.findProxy = MimicTransportService.instance.routeFor;
    return client;
  }
}
