import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vpn_subscription_app/config/api_config.dart';
import 'package:vpn_subscription_app/services/api_host_service.dart';

const _template = 'https://*.api.appvle.store';
final _urlWithLabel = RegExp(r'^https://([a-z0-9]{12})\.api\.appvle\.store$');

String get _currentHost => Uri.parse(ApiConfig.baseUrl).host;

Future<SharedPreferences> _flushedPrefs() async {
  await pumpEventQueue();
  return SharedPreferences.getInstance();
}

void main() {
  late ApiHostService host;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    ApiConfig.baseUrl = ApiConfig.defaultBaseUrl;
    host = ApiHostService(random: Random(1));
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

    test('a plain URL works as before and turns template mode off', () async {
      host.apply(_template);
      expect(host.apply('https://plain.example.com//'), isTrue);
      expect(ApiConfig.baseUrl, 'https://plain.example.com');

      await expectLater(
        host.withFailover(
          (_) async => throw const SocketException('Connection refused'),
        ),
        throwsA(isA<SocketException>()),
      );
      expect(ApiConfig.baseUrl, 'https://plain.example.com');
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
    Future<http.Response> Function(String) failingOn(
      String blocked, {
      Object? error,
      List<String>? attempts,
    }) =>
        (base) async {
          attempts?.add(base);
          if (Uri.parse(base).host == blocked) {
            throw error ?? const SocketException('Connection reset by peer');
          }
          return http.Response('ok', 200);
        };

    test('a blocked label is replaced, persisted and retried on the new host',
        () async {
      final blocked = _currentHost;
      final attempts = <String>[];

      final response = await host.withFailover(
        failingOn(blocked, attempts: attempts),
      );

      expect(response.statusCode, 200);
      expect(attempts, hasLength(2));
      expect(_currentHost, isNot(blocked));
      expect(attempts.last, ApiConfig.baseUrl);
      expect(_urlWithLabel.hasMatch(ApiConfig.baseUrl), isTrue);
      expect(
        (await _flushedPrefs()).getString('api_host_label'),
        _urlWithLabel.firstMatch(ApiConfig.baseUrl)!.group(1),
      );
    });

    test('every kind of transport failure switches the label', () async {
      for (final error in <Object>[
        const SocketException('Failed host lookup'),
        const HandshakeException('Connection terminated during handshake'),
        TimeoutException('no answer'),
        http.ClientException('Connection closed before full header'),
      ]) {
        final blocked = _currentHost;
        await host.withFailover(failingOn(blocked, error: error));
        expect(_currentHost, isNot(blocked), reason: '$error');
      }
    });

    test('a successful response leaves the label alone', () async {
      final before = ApiConfig.baseUrl;
      final response =
          await host.withFailover((_) async => http.Response('ok', 200));
      expect(response.statusCode, 200);
      expect(ApiConfig.baseUrl, before);
    });

    test('error HTTP statuses switch the label and are retried', () async {
      for (final status in [404, 429, 500, 503]) {
        final before = _currentHost;
        var calls = 0;

        final response = await host.withFailover((_) async {
          calls++;
          return http.Response('boom', calls == 1 ? status : 200);
        });

        expect(calls, 2, reason: '$status');
        expect(response.statusCode, 200, reason: '$status');
        expect(_currentHost, isNot(before), reason: '$status');
      }
    });

    test('a plain URL is left alone on any failure', () async {
      host.apply('https://plain.example.com');
      await expectLater(
        host.withFailover(
          (_) async => throw const SocketException('Network is unreachable'),
        ),
        throwsA(isA<SocketException>()),
      );
      final response =
          await host.withFailover((_) async => http.Response('boom', 500));
      expect(response.statusCode, 500);
      expect(ApiConfig.baseUrl, 'https://plain.example.com');
    });

    test('application errors never switch the label', () async {
      final before = ApiConfig.baseUrl;
      await expectLater(
        host.withFailover((_) async => throw const FormatException('json')),
        throwsFormatException,
      );
      expect(ApiConfig.baseUrl, before);
    });

    test('a plain URL applied while the request was in flight wins', () async {
      await expectLater(
        host.withFailover((_) async {
          host.apply('https://plain.example.com');
          throw const SocketException('reset');
        }),
        throwsA(isA<SocketException>()),
      );
      expect(ApiConfig.baseUrl, 'https://plain.example.com');
    });

    test('concurrent failures share one new label', () async {
      final blocked = _currentHost;
      final attempts = <String>[];

      final responses = await Future.wait([
        for (var i = 0; i < 3; i++)
          host.withFailover(failingOn(blocked, attempts: attempts)),
      ]);

      expect(responses.map((r) => r.statusCode), everyElement(200));
      expect(_currentHost, isNot(blocked));
      final retried =
          attempts.where((a) => Uri.parse(a).host != blocked).toSet();
      expect(retried, hasLength(1), reason: 'все повторы — на одну новую метку');
      expect(retried.single, ApiConfig.baseUrl);
    });

    test('a non-idempotent request is not re-sent after a timeout, '
        'but the label still changes',
        () async {
      final before = _currentHost;
      var sends = 0;

      await expectLater(
        host.withFailover((_) async {
          sends++;
          throw TimeoutException('server may have processed it');
        }, idempotent: false),
        throwsA(isA<TimeoutException>()),
      );

      expect(sends, 1);
      expect(_currentHost, isNot(before));
    });

    test('a non-idempotent request is re-sent after a plain connection error',
        () async {
      final blocked = _currentHost;

      final response = await host.withFailover(
        failingOn(blocked),
        idempotent: false,
      );

      expect(response.statusCode, 200);
      expect(_currentHost, isNot(blocked));
    });

    test('a non-idempotent request is not re-sent after an error response, '
        'but the label still changes',
        () async {
      final before = _currentHost;
      var sends = 0;

      final response = await host.withFailover((_) async {
        sends++;
        return http.Response('boom', 500);
      }, idempotent: false);

      expect(sends, 1);
      expect(response.statusCode, 500);
      expect(_currentHost, isNot(before));
    });

    test('the label changes again every time the new one gets blocked',
        () async {
      final seen = <String>{_currentHost};
      for (var i = 0; i < 6; i++) {
        final blocked = _currentHost;
        await host.withFailover(failingOn(blocked));
        expect(seen.add(_currentHost), isTrue, reason: 'switch #$i');
      }
      expect(seen, hasLength(7));
    });
  });
}
