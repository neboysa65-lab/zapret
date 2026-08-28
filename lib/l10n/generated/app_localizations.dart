import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_ru.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('ru'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Vless VPN'**
  String get appTitle;

  /// No description provided for @connect.
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get connect;

  /// No description provided for @disconnect.
  ///
  /// In en, this message translates to:
  /// **'Disconnect'**
  String get disconnect;

  /// No description provided for @connected.
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get connected;

  /// No description provided for @disconnected.
  ///
  /// In en, this message translates to:
  /// **'Disconnected'**
  String get disconnected;

  /// No description provided for @connecting.
  ///
  /// In en, this message translates to:
  /// **'Connecting...'**
  String get connecting;

  /// No description provided for @selectServer.
  ///
  /// In en, this message translates to:
  /// **'Select Server'**
  String get selectServer;

  /// No description provided for @clickToSelect.
  ///
  /// In en, this message translates to:
  /// **'Click to select'**
  String get clickToSelect;

  /// No description provided for @autoSelectBest.
  ///
  /// In en, this message translates to:
  /// **'Auto Select (Best)'**
  String get autoSelectBest;

  /// No description provided for @optimized.
  ///
  /// In en, this message translates to:
  /// **'Optimized'**
  String get optimized;

  /// No description provided for @manualSelection.
  ///
  /// In en, this message translates to:
  /// **'Manual Selection'**
  String get manualSelection;

  /// No description provided for @unknown.
  ///
  /// In en, this message translates to:
  /// **'Unknown'**
  String get unknown;

  /// No description provided for @securelyConnected.
  ///
  /// In en, this message translates to:
  /// **'SECURELY CONNECTED'**
  String get securelyConnected;

  /// No description provided for @connectingUpper.
  ///
  /// In en, this message translates to:
  /// **'CONNECTING...'**
  String get connectingUpper;

  /// No description provided for @disconnectingUpper.
  ///
  /// In en, this message translates to:
  /// **'DISCONNECTING...'**
  String get disconnectingUpper;

  /// No description provided for @notConnected.
  ///
  /// In en, this message translates to:
  /// **'NOT CONNECTED'**
  String get notConnected;

  /// No description provided for @download.
  ///
  /// In en, this message translates to:
  /// **'DOWNLOAD'**
  String get download;

  /// No description provided for @upload.
  ///
  /// In en, this message translates to:
  /// **'UPLOAD'**
  String get upload;

  /// No description provided for @connectNow.
  ///
  /// In en, this message translates to:
  /// **'CONNECT NOW'**
  String get connectNow;

  /// No description provided for @disconnectUpper.
  ///
  /// In en, this message translates to:
  /// **'DISCONNECT'**
  String get disconnectUpper;

  /// No description provided for @daysRemaining.
  ///
  /// In en, this message translates to:
  /// **'days remaining'**
  String get daysRemaining;

  /// No description provided for @selectLocation.
  ///
  /// In en, this message translates to:
  /// **'Select Location'**
  String get selectLocation;

  /// No description provided for @serversCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{No servers} =1{1 server} other{{count} servers}}'**
  String serversCount(num count);

  /// No description provided for @autoSelect.
  ///
  /// In en, this message translates to:
  /// **'Auto Select'**
  String get autoSelect;

  /// No description provided for @optimalServer.
  ///
  /// In en, this message translates to:
  /// **'Optimal server by speed'**
  String get optimalServer;

  /// No description provided for @serversNotFound.
  ///
  /// In en, this message translates to:
  /// **'Servers not found'**
  String get serversNotFound;

  /// No description provided for @refresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get refresh;

  /// No description provided for @serverNotSelected.
  ///
  /// In en, this message translates to:
  /// **'Server not selected'**
  String get serverNotSelected;

  /// No description provided for @failedToLoadServers.
  ///
  /// In en, this message translates to:
  /// **'Failed to load servers'**
  String get failedToLoadServers;

  /// No description provided for @settings.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// No description provided for @supportTelegram.
  ///
  /// In en, this message translates to:
  /// **'Support Telegram Chat'**
  String get supportTelegram;

  /// No description provided for @privacyPolicy.
  ///
  /// In en, this message translates to:
  /// **'Privacy Policy'**
  String get privacyPolicy;

  /// No description provided for @version.
  ///
  /// In en, this message translates to:
  /// **'Version'**
  String get version;

  /// No description provided for @rateUs.
  ///
  /// In en, this message translates to:
  /// **'Rate Us'**
  String get rateUs;

  /// No description provided for @privacyPolicyContent.
  ///
  /// In en, this message translates to:
  /// **'Vless VPN Privacy Policy\nLast Updated: February 22, 2026\n\nWelcome to Vless VPN. We respect and protect your privacy. This Privacy Policy explains how we collect, use, and safeguard your information when you use our mobile application and services.\n\nBy using Vless VPN, you agree to the terms of this Policy. If you do not agree with them, please discontinue using the application.\n\n1. Introduction and General Provisions\nVless VPN is an application designed to provide secure and anonymous internet access. We built our service on the principle of minimal data collection. Unlike many other services, we do not log your internet activity.\n\nOur Privacy Policy applies to all data that may be obtained during the use of the Vless VPN application.\n\n2. Information We Collect (and Do Not Collect)\nWe designed Vless VPN so that it does not collect information about your online activities. We adhere to a \"No Logs\" policy.\n\n2.1. We DO NOT Collect:\nBrowsing History: We do not track which websites you visit.\nTraffic Content: We do not read or record the content of your data.\nDNS Queries: Your DNS queries are processed anonymously (using technologies like FakeDNS) and are not logged.\nPrecise Location Data: We do not track your GPS location.\n\n2.2. Limited Technical Information:\nTo ensure service operability, improve connection quality, and comply with app store requirements (Google Play), we may receive certain minimal technical information that does not personally identify the user:\nDevice Information: Device model, operating system version (Android), app version. This is needed for correct operation and sending update notifications.\nNetwork Information: Basic information about connection type (Wi-Fi, mobile network) to optimize connection speed. Your real IP address is processed only at the moment of connection to establish the tunnel and is not stored on our servers.\n\n3. Required App Permissions\nTo function, Vless VPN needs to request certain permissions from your device:\nINTERNET (Full Network Access): Required to establish VPN connection and transmit data through our servers.\nACCESS_NETWORK_STATE (View Network Connections): Needed to check your connection status (e.g., to reconnect VPN when network changes or warn you about no internet).\nFOREGROUND_SERVICE (Run in Foreground): Necessary to maintain a stable and persistent VPN connection even when the app is minimized.\nWAKE_LOCK (Prevent Sleep): Used to prevent Android from killing the VPN service during long device idle times.\nPOST_NOTIFICATIONS (Send Notifications): To display VPN connection status in the status bar.\n\nWe do not request permissions to access your contacts, calendar, microphone, or files, as they are not needed for the service.\n\n4. Ad Networks and Trackers (Transparency)\nIn the current version of Vless VPN, ad network code may be used to display ads, allowing us to support free servers. Static code analysis may show signatures of trackers categorized as \"advertisement\".\nThis means ad modules may collect anonymized data to show targeted ads, such as:\nAdvertising ID (Ad ID);\nInformation about how you interact with ads (clicks, views).\nThese data are collected by ad partners (third parties) according to their own privacy policies and are not linked to your activity inside the VPN tunnel. You can reset your Advertising ID or opt-out of ad personalization in your Android device settings.\n\n5. Data Transfer to Third Parties\nWe do not sell or transfer your personal data to third parties for purposes unrelated to the app\'s operation.\nAs stated in Section 4, data may be processed by ad partners. The Google Play app page states that \"the developer declares the app does not share user data with other companies\". This refers to user data generated inside the VPN tunnel (your traffic) and does not contradict the presence of standard ad SDKs that work outside the tunnel and collect install/ad interaction data.\n\n6. Data Security\nWe take reasonable measures to protect your information. Connection to our servers is secured by modern cryptographic protocols.\nImportant Note on Encryption: Like many other VPN apps, Vless VPN redirects your traffic through an encrypted tunnel. However, according to the Google Play app page info, transmitted data does not undergo additional end-to-end encryption on top of the tunnel if you visit non-HTTPS sites (though the vast majority of modern sites use HTTPS, providing app-level encryption).\n\n7. Data Retention and Deletion\nRetention: We do not store data you transmit via VPN. Short-term technical logs (e.g., server peak load) are anonymized and deleted within a few hours.\nDeletion: Since we do not collect your personal data (we do not require email registration, etc.), we physically do not have a database with your info to delete upon request. The developer does not provide a data deletion mechanism because no data is collected.\n\n8. Children\nOur service is not intended for persons under 13. We do not knowingly collect personal information from children.\n\n9. Changes to Privacy Policy\nWe may update our Privacy Policy from time to time. We will notify you of changes by posting the new version on this page and updating the \"Last Updated\" date.\n\n10. Contact Us\nIf you have questions regarding this Privacy Policy or the processing of your data, please contact us via email: admingwvpn@proton.me'**
  String get privacyPolicyContent;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'ru'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'ru':
      return AppLocalizationsRu();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
