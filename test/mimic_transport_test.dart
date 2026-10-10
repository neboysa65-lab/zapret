import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/services/mimic_transport_service.dart';

void main() {
  final now = DateTime(2026, 10, 10, 12);

  test('routes nothing when tunnel is down or disabled', () {
    expect(
      MimicTransportService.shouldRoute(
        host: 'api.appvle.store',
        enabled: true,
        tunnelUp: false,
        cooldownUntil: DateTime.fromMillisecondsSinceEpoch(0),
        hostSuffixes: ['appvle.store'],
        now: now,
      ),
      isFalse,
    );
    expect(
      MimicTransportService.shouldRoute(
        host: 'api.appvle.store',
        enabled: false,
        tunnelUp: true,
        cooldownUntil: DateTime.fromMillisecondsSinceEpoch(0),
        hostSuffixes: ['appvle.store'],
        now: now,
      ),
      isFalse,
    );
  });

  test('respects the cooldown window', () {
    expect(
      MimicTransportService.shouldRoute(
        host: 'api.appvle.store',
        enabled: true,
        tunnelUp: true,
        cooldownUntil: now.add(const Duration(minutes: 1)),
        hostSuffixes: ['appvle.store'],
        now: now,
      ),
      isFalse,
    );
    expect(
      MimicTransportService.shouldRoute(
        host: 'api.appvle.store',
        enabled: true,
        tunnelUp: true,
        cooldownUntil: now,
        hostSuffixes: ['appvle.store'],
        now: now,
      ),
      isTrue,
    );
  });

  test('matches whitelisted suffixes only', () {
    const suffixes = ['appvle.store', 'gw-vpn.click'];
    for (final host in [
      'api.appvle.store',
      'appvle.store',
      'a1b2c3.appvle.store',
      'panel.gw-vpn.click',
    ]) {
      expect(
        MimicTransportService.shouldRoute(
          host: host,
          enabled: true,
          tunnelUp: true,
          cooldownUntil: DateTime.fromMillisecondsSinceEpoch(0),
          hostSuffixes: suffixes,
          now: now,
        ),
        isTrue,
        reason: host,
      );
    }
    for (final host in [
      '',
      'example.com',
      'evile-appvle.store',
      'appvle.store.attacker.net',
    ]) {
      expect(
        MimicTransportService.shouldRoute(
          host: host,
          enabled: true,
          tunnelUp: true,
          cooldownUntil: DateTime.fromMillisecondsSinceEpoch(0),
          hostSuffixes: suffixes,
          now: now,
        ),
        isFalse,
        reason: host,
      );
    }
  });
}
