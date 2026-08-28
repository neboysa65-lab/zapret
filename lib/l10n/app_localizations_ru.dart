// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Russian (`ru`).
class AppLocalizationsRu extends AppLocalizations {
  AppLocalizationsRu([String locale = 'ru']) : super(locale);

  @override
  String get appTitle => 'WG VPN';

  @override
  String get connect => 'Подключить';

  @override
  String get disconnect => 'Отключить';

  @override
  String get connected => 'Подключено';

  @override
  String get disconnected => 'Отключено';

  @override
  String get connecting => 'Подключение...';

  @override
  String get selectServer => 'Выбрать сервер';

  @override
  String get clickToSelect => 'Нажмите для выбора';

  @override
  String get autoSelectBest => 'Авто выбор (лучший)';

  @override
  String get optimized => 'Оптимизировано';

  @override
  String get manualSelection => 'Выбрано вручную';

  @override
  String get unknown => 'Неизвестно';

  @override
  String get securelyConnected => 'БЕЗОПАСНО ПОДКЛЮЧЕНО';

  @override
  String get connectingUpper => 'ПОДКЛЮЧЕНИЕ...';

  @override
  String get disconnectingUpper => 'ОТКЛЮЧЕНИЕ...';

  @override
  String get notConnected => 'НЕ ПОДКЛЮЧЕНО';

  @override
  String get download => 'СКАЧАТЬ';

  @override
  String get upload => 'ЗАГРУЗИТЬ';

  @override
  String get connectNow => 'ПОДКЛЮЧИТЬСЯ';

  @override
  String get disconnectUpper => 'ОТКЛЮЧИТЬ';

  @override
  String get daysRemaining => 'осталось дней';

  @override
  String get selectLocation => 'Выбор локации';

  @override
  String serversCount(num count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count серверов',
      few: '$count сервера',
      one: '1 сервер',
      zero: 'Нет серверов',
    );
    return '$_temp0';
  }

  @override
  String get autoSelect => 'Авто выбор';

  @override
  String get optimalServer => 'Оптимальный сервер по скорости';

  @override
  String get serversNotFound => 'Серверы не найдены';

  @override
  String get refresh => 'Обновить';

  @override
  String get serverNotSelected => 'Сервер не выбран';

  @override
  String get failedToLoadServers => 'Не удалось загрузить серверы';
}
