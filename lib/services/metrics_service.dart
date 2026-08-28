import 'dart:convert';
import 'package:http/http.dart' as http;

import '../config/api_config.dart';

class MetricsService {
  // Домен панели берём из ApiConfig (его перезаписывает Remote Config при
  // старте). Меняется только адрес — путь эндпоинта остаётся прежним.
  static const String _metricsPath = '/api/bandwidth-stats/nodes/realtime';
  String get _metricsUrl => '${ApiConfig.panelBaseUrl}$_metricsPath';
  // JWT read-only token
  static const String _token =
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1dWlkIjoiZWNhZjAzNjQtMDIyZi00ZDY3LTljMzEtZjI4MWFkMGMxZGMyIiwidXNlcm5hbWUiOm51bGwsInJvbGUiOiJBUEkiLCJpYXQiOjE3NzM4NTg1MDcsImV4cCI6MTA0MTM3NzIxMDd9.VB_EbzIvTSemNVuR8VHkByHwARllUQPYSeY22IVtz5U';

  /// Returns a map of nodeName -> totalSpeedBps (live traffic)
  Future<Map<String, int>> fetchMetrics() async {
    try {
      final response = await http
          .get(
            Uri.parse(_metricsUrl),
            headers: {'Authorization': 'Bearer $_token'},
          )
          .timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final Map<String, dynamic> rawData = json.decode(response.body);
        final Map<String, int> metrics = {};

        // Realtime endpoint returns {"response": [...]} — flat array
        if (rawData.containsKey('response') && rawData['response'] is List) {
          final List<dynamic> nodesList = rawData['response'] as List<dynamic>;
          for (var item in nodesList) {
            final nodeName = item['nodeName']?.toString();
            final speed = (item['totalSpeedBps'] as num?)?.toInt() ?? 0;
            if (nodeName != null) {
              metrics[nodeName] = speed;
            }
          }
        }
        return metrics;
      } else {
        print('MetricsService failed with status: ${response.statusCode}');
      }
    } catch (e) {
      print('MetricsService error: $e');
    }
    return {};
  }
}
