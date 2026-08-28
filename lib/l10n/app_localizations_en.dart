// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'WG VPN';

  @override
  String get connect => 'Connect';

  @override
  String get disconnect => 'Disconnect';

  @override
  String get connected => 'Connected';

  @override
  String get disconnected => 'Disconnected';

  @override
  String get connecting => 'Connecting...';

  @override
  String get selectServer => 'Select Server';

  @override
  String get clickToSelect => 'Click to select';

  @override
  String get autoSelectBest => 'Auto Select (Best)';

  @override
  String get optimized => 'Optimized';

  @override
  String get manualSelection => 'Manual Selection';

  @override
  String get unknown => 'Unknown';

  @override
  String get securelyConnected => 'SECURELY CONNECTED';

  @override
  String get connectingUpper => 'CONNECTING...';

  @override
  String get disconnectingUpper => 'DISCONNECTING...';

  @override
  String get notConnected => 'NOT CONNECTED';

  @override
  String get download => 'DOWNLOAD';

  @override
  String get upload => 'UPLOAD';

  @override
  String get connectNow => 'CONNECT NOW';

  @override
  String get disconnectUpper => 'DISCONNECT';

  @override
  String get daysRemaining => 'days remaining';

  @override
  String get selectLocation => 'Select Location';

  @override
  String serversCount(num count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count servers',
      one: '1 server',
      zero: 'No servers',
    );
    return '$_temp0';
  }

  @override
  String get autoSelect => 'Auto Select';

  @override
  String get optimalServer => 'Optimal server by speed';

  @override
  String get serversNotFound => 'Servers not found';

  @override
  String get refresh => 'Refresh';

  @override
  String get serverNotSelected => 'Server not selected';

  @override
  String get failedToLoadServers => 'Failed to load servers';
}
