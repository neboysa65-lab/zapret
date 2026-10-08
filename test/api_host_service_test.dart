import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vpn_subscription_app/config/api_config.dart';
import 'package:vpn_subscription_app/services/api_host_service.dart';

const _template = 'https://*.api.appvle.store';
final _urlWithLabel = RegExp(r'^https://([a-z0-9]{12})\.api\.appvle\.store$');

/// Сеть для проб: часть хостов отказывает, часть молчит (пакеты дропаются).
class FakeNetwork {
  final dead = <String>{};
  final silent = <String>{};
  bool offline = false;
  int status = 200;
  void Function()? onFirstProbe;
  final probes = <Uri>[];

  late final MockClient client = MockClient((request) async {
    probes.add(request.url);
    final hook = onFirstProbe;
    onFirstProbe = null;
    hook?.call();
    final host = request.url.host;
    if (offline || dead.contains(host)) {
      throw const SocketException('Failed host lookup');
    }
    if (silent.contains(host)) return Completer<http.Response>().future;
    return http.Response('[]', status);
  });
}

String get _currentHost => Uri.parse(ApiConfig.baseUrl).host;

Future<SharedPreferences> _flushedPrefs() async {
  await pumpEventQueue();
  return SharedPreferences.getInstance();
}

void main() {
  late FakeNetwork net;
  late DateTime clock;
  late ApiHostService host;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    ApiConfig.baseUrl = ApiConfig.defaultBaseUrl;
    net = FakeNetwork();
    clock = DateTime(2026, 1, 1);
    host = ApiHostService(
      client: net.client,
      random: Random(1),
      now: () => clock,
      probeTimeout: const Duration(milliseconds: 50),
    );
  });

  group('ApiHostTemplate', () {
    test('parses wildcard forms and normalizes them', () {
      for (final raw in [
        '*.api.appvle.store',
        'https://*.api.appvle.store',
        ' HTTPS://*.API.AppVle.store/ ',
      ]) {
        final template = ApiHostTemplate.tryParse(raw)!;
        expect(template.toString(), _template, reason: raw);
        expect(template.build('abc123'), 'https://abc123.api.appvle.store');
      }
      expect(
        ApiHostTemplate.tryParse('http://*.dev.example.com:8443')!.build('x'),
        'http://x.dev.example.com:8443',
      );
    });

    test('rejects anything but one leftmost wildcard over a real domain', () {
      for (final raw in [
        '',
        'https://api.appvle.store',
        'https://vleapi.vpnhub.xyz',
        '*',
        '*.',
        '*.com',
        'https://*.com',
        'api.*.appvle.store',
        '*.*.appvle.store',
        '**.appvle.store',
        'a*.appvle.store',
        '*.api.appvle.store/v1',
        '*.api.appvle.store:0',
        '*.api.appvle.store:70000',
        'ftp://*.appvle.store',
        '*.-bad.appvle.store',
        '*.api appvle.store',
      ]) {
        expect(ApiHostTemplate.tryParse(raw), isNull, reason: raw);
      }
    });
  });

  group('apply', () {
    test('template gets a random label that is persisted and then kept',
        () async {
      expect(host.apply('*.api.appvle.store'), isTrue);
      final label = _urlWithLabel.firstMatch(ApiConfig.baseUrl)!.group(1)!;

      final prefs = await _flushedPrefs();
      expect(prefs.getString('api_host_label'), label);
      expect(prefs.getString('api_host_source'), _template);

      // Обновление Remote Config не должно тасовать метку.
      host.apply(_template);
      expect(ApiConfig.baseUrl, 'https://$label.api.appvle.store');

      // Смена зоны сохраняет метку пользователя.
      host.apply('*.api2.example.org');
      expect(ApiConfig.baseUrl, 'https://$label.api2.example.org');
    });

    test('plain URL works as before and switches template mode off', () async {
      host.apply(_template);
      expect(host.apply('https://plain.example.com//'), isTrue);
      expect(ApiConfig.baseUrl, 'https://plain.example.com');

      await expectLater(
        host.withFailover(
          (_) async => throw const SocketException('Connection refused'),
        ),
        throwsA(isA<SocketException>()),
      );
      expect(net.probes, isEmpty);
    });

    test('invalid values are ignored', () {
      host.apply('https://stable.example.com');
      for (final raw in [
        '',
        'ftp://x.y',
        'https://',
        'https://*.api.appvle.store/path',
        'https://bad host',
        'not a url',
      ]) {
        expect(host.apply(raw), isFalse, reason: raw);
      }
      expect(ApiConfig.baseUrl, 'https://stable.example.com');
    });

    test('the in-app default is not stored as an override', () async {
      SharedPreferences.setMockInitialValues({
        'api_host_source': 'https://old.example.com',
      });
      host.apply('https://new.example.com');
      expect(
        (await _flushedPrefs()).getString('api_host_source'),
        'https://new.example.com',
      );

      host.apply(ApiConfig.defaultBaseUrl);
      expect((await _flushedPrefs()).getString('api_host_source'), isNull);
    });
  });

  group('restore', () {
    test('brings back the last Remote Config value and label', () async {
      SharedPreferences.setMockInitialValues({
        'api_host_source': _template,
        'api_host_label': 'k3x9a0qf7d2m',
      });
      await host.restore();
      expect(ApiConfig.baseUrl, 'https://k3x9a0qf7d2m.api.appvle.store');
    });

    test('brings back a stored plain URL', () async {
      SharedPreferences.setMockInitialValues({
        'api_host_source': 'https://mirror.example.com',
      });
      await host.restore();
      expect(ApiConfig.baseUrl, 'https://mirror.example.com');
    });

    test('falls back to the default on nothing or garbage', () async {
      await host.restore();
      expect(ApiConfig.baseUrl, ApiConfig.defaultBaseUrl);

      SharedPreferences.setMockInitialValues({'api_host_source': 'ftp://nope'});
      await host.restore();
      expect(ApiConfig.baseUrl, ApiConfig.defaultBaseUrl);
    });

    test('replaces a malformed saved label', () async {
      SharedPreferences.setMockInitialValues({
        'api_host_source': _template,
        'api_host_label': 'BAD LABEL!',
      });
      await host.restore();
      expect(_urlWithLabel.hasMatch(ApiConfig.baseUrl), isTrue);
    });
  });

  group('isConnectionFailure', () {
    test('covers transport errors only', () {
      for (final error in <Object>[
        const SocketException('reset'),
        const HandshakeException('tls'),
        const TlsException('tls'),
        const HttpException('closed'),
        TimeoutException('slow'),
        http.ClientException('closed'),
      ]) {
        expect(ApiHostService.isConnectionFailure(error), isTrue,
            reason: '$error');
      }
      for (final error in <Object>[
        const FormatException('json'),
        StateError('Not authenticated'),
        Exception('getMe failed: 500'),
      ]) {
        expect(ApiHostService.isConnectionFailure(error), isFalse,
            reason: '$error');
      }
    });
  });

  group('withFailover', () {
    setUp(() => host.apply(_template));

    /// Запрос, который падает, пока адрес указывает на [blocked].
    Future<String> Function(String) failingOn(
      String blocked, {
      Object? error,
      List<String>? attempts,
    }) =>
        (base) async {
          attempts?.add(base);
          if (Uri.parse(base).host == blocked) {
            throw error ?? const SocketException('Connection reset by peer');
          }
          return base;
        };

    test('blocked label: switches, persists and retries on the new host',
        () async {
      final blocked = _currentHost;
      net.dead.add(blocked);
      final attempts = <String>[];

      final result = await host.withFailover(
        failingOn(blocked, attempts: attempts),
      );

      expect(attempts, hasLength(2));
      expect(result, ApiConfig.baseUrl);
      expect(_currentHost, isNot(blocked));
      expect(_urlWithLabel.hasMatch(ApiConfig.baseUrl), isTrue);
      expect(
        (await _flushedPrefs()).getString('api_host_label'),
        _urlWithLabel.firstMatch(ApiConfig.baseUrl)!.group(1),
      );
      expect(net.probes.map((u) => u.path).toSet(), {'/api/v1/tariffs'});
    });

    test('every kind of transport failure triggers a rotation', () async {
      for (final error in <Object>[
        const SocketException('Failed host lookup'),
        const HandshakeException('Connection terminated during handshake'),
        TimeoutException('no answer'),
        http.ClientException('Connection closed before full header'),
      ]) {
        final blocked = _currentHost;
        net.dead.add(blocked);
        await host.withFailover(failingOn(blocked, error: error));
        expect(_currentHost, isNot(blocked), reason: '$error');
      }
    });

    test('a host that silently drops packets is rotated after the probe times out',
        () async {
      final blocked = _currentHost;
      net.silent.add(blocked);

      await host.withFailover(
        failingOn(blocked, error: TimeoutException('no answer')),
      );

      expect(_currentHost, isNot(blocked));
    });

    test('application errors never rotate or probe', () async {
      final before = ApiConfig.baseUrl;
      await expectLater(
        host.withFailover((_) async => throw const FormatException('json')),
        throwsFormatException,
      );
      // HTTP-статус — обычный ответ, до failover он не доходит.
      expect(await host.withFailover((_) async => 503), 503);
      expect(ApiConfig.baseUrl, before);
      expect(net.probes, isEmpty);
    });

    test('a live host with a transient error is left alone', () async {
      final before = ApiConfig.baseUrl;
      net.status = 500; // сервер отвечает, пусть и ошибкой
      await expectLater(
        host.withFailover((_) async => throw TimeoutException('slow')),
        throwsA(isA<TimeoutException>()),
      );
      expect(ApiConfig.baseUrl, before);
    });

    test('offline: keeps the label, pauses probing, resumes after the pause',
        () async {
      final before = ApiConfig.baseUrl;
      net.offline = true;
      Future<String> call() => host.withFailover(
            (_) async => throw const SocketException('Network is unreachable'),
          );

      await expectLater(call(), throwsA(isA<SocketException>()));
      expect(net.probes, hasLength(3)); // текущий + две свежие метки
      expect(ApiConfig.baseUrl, before);

      await expectLater(call(), throwsA(isA<SocketException>()));
      expect(net.probes, hasLength(3), reason: 'pause: no new probes');

      clock = clock.add(const Duration(seconds: 21));
      net.offline = false;
      net.dead.add(_currentHost);
      await host.withFailover(failingOn(_currentHost));
      expect(ApiConfig.baseUrl, isNot(before));
    });

    test('concurrent failures share one rotation', () async {
      final blocked = _currentHost;
      net.dead.add(blocked);

      final results = await Future.wait([
        for (var i = 0; i < 3; i++) host.withFailover(failingOn(blocked)),
      ]);

      expect(results.toSet(), {ApiConfig.baseUrl});
      expect(net.probes, hasLength(3));
    });

    test('non-idempotent request is not re-sent after a timeout but heals',
        () async {
      final blocked = _currentHost;
      net.dead.add(blocked);
      var sends = 0;

      await expectLater(
        host.withFailover((_) async {
          sends++;
          throw TimeoutException('server may have processed it');
        }, idempotent: false),
        throwsA(isA<TimeoutException>()),
      );

      expect(sends, 1);
      expect(_currentHost, isNot(blocked));
    });

    test('non-idempotent request is re-sent after a plain connection error',
        () async {
      final blocked = _currentHost;
      net.dead.add(blocked);

      final result = await host.withFailover(
        failingOn(blocked),
        idempotent: false,
      );

      expect(result, ApiConfig.baseUrl);
    });

    test('rotates again every time the current label gets blocked', () async {
      final seen = <String>{_currentHost};
      for (var i = 0; i < 6; i++) {
        final blocked = _currentHost;
        net.dead.add(blocked);
        await host.withFailover(failingOn(blocked));
        expect(seen.add(_currentHost), isTrue, reason: 'rotation #$i');
      }
      expect(seen, hasLength(7));
    });

    test('an address change from Remote Config during the probe wins',
        () async {
      final blocked = _currentHost;
      net.dead.add(blocked);
      net.onFirstProbe = () => host.apply('https://plain.example.com');
      final attempts = <String>[];

      await host.withFailover(failingOn(blocked, attempts: attempts));

      expect(attempts.last, 'https://plain.example.com');
      expect(ApiConfig.baseUrl, 'https://plain.example.com');
    });
  });
}
