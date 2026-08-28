import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

/// Background message handler. Must be a top-level function annotated with
/// @pragma('vm:entry-point') so AOT keeps it for the background isolate.
///
/// For "notification"-type FCM messages, Android system shows the tray
/// notification automatically — we only get this callback for logging /
/// data processing.
@pragma('vm:entry-point')
Future<void> firebaseBackgroundHandler(RemoteMessage message) async {
  if (kDebugMode) {
    debugPrint('FCM bg: id=${message.messageId} data=${message.data}');
  }
}

class NotificationService {
  NotificationService._();
  static final NotificationService instance = NotificationService._();

  // Channel ID must match meta-data
  // `com.google.firebase.messaging.default_notification_channel_id`
  // declared in AndroidManifest.xml.
  static const String _channelId = 'default_channel';
  static const String _channelName = 'Уведомления';
  static const String _channelDesc = 'Важные уведомления приложения';

  final FirebaseMessaging _messaging = FirebaseMessaging.instance;
  final FlutterLocalNotificationsPlugin _local =
      FlutterLocalNotificationsPlugin();

  bool _initialized = false;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

    FirebaseMessaging.onBackgroundMessage(firebaseBackgroundHandler);

    // Android 13+ runtime permission for POST_NOTIFICATIONS.
    // On older versions this resolves to authorized without a system dialog.
    final settings = await _messaging.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );
    if (kDebugMode) {
      debugPrint('FCM permission: ${settings.authorizationStatus}');
    }

    // Local notifications — used to show tray notifications when the app is
    // in foreground (Android does not show "notification"-type pushes itself
    // when the app is in foreground).
    const androidInit =
        AndroidInitializationSettings('ic_stat_wg_vpn');
    const initSettings = InitializationSettings(android: androidInit);
    await _local.initialize(
      initSettings,
      onDidReceiveNotificationResponse: _onLocalTap,
    );

    // Register default channel (Android 8+ requires explicit channels).
    await _local
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(
          const AndroidNotificationChannel(
            _channelId,
            _channelName,
            description: _channelDesc,
            importance: Importance.high,
          ),
        );

    // Subscribe to broadcast topic — enables Firebase Console "Target = topic"
    // delivery in addition to "User segment = All users".
    try {
      await _messaging.subscribeToTopic('all_users');
    } catch (e) {
      debugPrint('subscribeToTopic failed: $e');
    }

    if (kDebugMode) {
      try {
        final token = await _messaging.getToken();
        debugPrint('FCM token: $token');
      } catch (e) {
        debugPrint('getToken failed: $e');
      }
    }

    FirebaseMessaging.onMessage.listen(_handleForeground);
    FirebaseMessaging.onMessageOpenedApp.listen(_handleOpenedApp);

    // App started from a tap on a notification (cold start).
    final initial = await _messaging.getInitialMessage();
    if (initial != null) {
      _handleOpenedApp(initial);
    }
  }

  void _handleForeground(RemoteMessage msg) {
    final notif = msg.notification;
    if (notif == null) return;

    _local.show(
      notif.hashCode,
      notif.title,
      notif.body,
      const NotificationDetails(
        android: AndroidNotificationDetails(
          _channelId,
          _channelName,
          channelDescription: _channelDesc,
          importance: Importance.high,
          priority: Priority.high,
          icon: 'ic_stat_wg_vpn',
        ),
      ),
      payload: msg.data['type'] as String?,
    );
  }

  void _handleOpenedApp(RemoteMessage msg) {
    if (kDebugMode) {
      debugPrint('FCM tap: data=${msg.data}');
    }
    // Hook for future deep-linking based on msg.data['type'].
  }

  void _onLocalTap(NotificationResponse response) {
    if (kDebugMode) {
      debugPrint('Local tap: ${response.payload}');
    }
  }
}
