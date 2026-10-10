import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/services/mimic_transport_service.dart';

void main() {
  final now = DateTime(2026, 10, 10, 12);
  final epoch = DateTime.fromMillisecondsSinceEpoch(0);
  const suffixes = ['appvle.store', 'gw-vpn.click'];

  test('falls back to direct when both tunnels are down', () {
    expect(
      MimicTransportService.decide(
        host: 'api.appvle.store',
        enabled: true,
        udpUp: false,
        tcpUp: false,
        udpCooldown: epoch,
        tcpCooldown: epoch,
        hostSuffixes: suffixes,
        now: now,
      ),
      'direct',
    );
    expect(
      MimicTransportService.decide(
        host: 'api.appvle.store',
        enabled: false,
        udpUp: true,
        tcpUp: true,
        udpCooldown: epoch,
        tcpCooldown: epoch,
        hostSuffixes: suffixes,
        now: now,
      ),
      'direct',
    );
  });

  test('udp wins while healthy, tcp takes over after udp cooldown', () {
    const cool = Duration(minutes: 1);
    expect(
      MimicTransportService.decide(
        host: 'api.appvle.store',
        enabled: true,
        udpUp: true,
        tcpUp: true,
        udpCooldown: epoch,
        tcpCooldown: epoch,
        hostSuffixes: suffixes,
        now: now,
      ),
      'udp',
    );
    expect(
      MimicTransportService.decide(
        host: 'api.appvle.store',
        enabled: true,
        udpUp: true,
        tcpUp: true,
        udpCooldown: now.add(cool),
        tcpCooldown: epoch,
        hostSuffixes: suffixes,
        now: now,
      ),
      'tcp',
    );
    expect(
      MimicTransportService.decide(
        host: 'api.appvle.store',
        enabled: true,
        udpUp: true,
        tcpUp: true,
        udpCooldown: now.add(cool),
        tcpCooldown: now.add(cool),
        hostSuffixes: suffixes,
        now: now,
      ),
      'direct',
    );
  });

  test('matches whitelisted suffixes only', () {
    for (final host in [
      'api.appvle.store',
      'appvle.store',
      'a1b2c3.appvle.store',
      'panel.gw-vpn.click',
    ]) {
      expect(
        MimicTransportService.decide(
          host: host,
          enabled: true,
          udpUp: true,
          tcpUp: false,
          udpCooldown: epoch,
          tcpCooldown: epoch,
          hostSuffixes: suffixes,
          now: now,
        ),
        'udp',
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
        MimicTransportService.decide(
          host: host,
          enabled: true,
          udpUp: true,
          tcpUp: true,
          udpCooldown: epoch,
          tcpCooldown: epoch,
          hostSuffixes: suffixes,
          now: now,
        ),
        'direct',
        reason: host,
      );
    }
  });
}
