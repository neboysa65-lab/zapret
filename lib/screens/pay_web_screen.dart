import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../config/api_config.dart';
import '../config/app_theme.dart';
import '../providers/auth_provider.dart';

/// Гибридный флоу оплаты:
///
/// 1. Страница ВЫБОРА ТАРИФА (наш сайт api-vlessvpn-app.vpnhub.xyz/pay?uid=...)
///    открывается ВНУТРИ приложения через WebView — как это было до
///    коммита 8a39afe.
/// 2. Как только сайт перенаправляет на платёжную форму
///    (https://payment.wata.pro/pay-form/<ID> — любой чужой домен),
///    ссылка перехватывается и открывается во ВНЕШНЕМ браузере.
///    Так банковские deep-link'и СБП (bank100000000111://...) обрабатывает
///    системный браузер, который умеет передавать их в приложение банка —
///    WebView на части устройств падал на них с ERR_UNKNOWN_URL_SCHEME.
///
/// Пока пользователь платит, экран поллит статус премиума (каждые 3 сек,
/// до 10 минут) и дополнительно проверяет его при возврате в приложение.
class PayWebScreen extends StatefulWidget {
  final String url;

  const PayWebScreen({super.key, required this.url});

  @override
  State<PayWebScreen> createState() => _PayWebScreenState();
}

class _PayWebScreenState extends State<PayWebScreen>
    with WidgetsBindingObserver {
  late final WebViewController _controller;
  Timer? _pollTimer;
  bool _pageLoading = true;
  bool _rendererCrashed = false;
  bool _closing = false;
  bool _refreshInFlight = false;
  bool _externalOpened = false;

  static const _pollInterval = Duration(seconds: 3);
  static const _timeout = Duration(minutes: 10);

  /// Хост нашего сайта с выбором тарифа — только он остаётся в WebView.
  static final String _inAppHost = Uri.parse(ApiConfig.baseUrl).host;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(AppColors.darkBackground)
      ..setNavigationDelegate(NavigationDelegate(
        onNavigationRequest: _handleNavigation,
        onPageStarted: (_) => setState(() {
          _pageLoading = true;
          _rendererCrashed = false;
        }),
        onPageFinished: (_) => setState(() => _pageLoading = false),
        onWebResourceError: (error) {
          if (error.errorCode == -1) {
            // Renderer crash — показываем кнопку перезагрузки
            if (mounted) {
              setState(() {
                _pageLoading = false;
                _rendererCrashed = true;
              });
            }
          }
        },
      ))
      ..loadRequest(Uri.parse(widget.url));

    _startPolling();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Пользователь вернулся из внешнего браузера / приложения банка —
    // сразу проверяем, не активировался ли премиум.
    if (state == AppLifecycleState.resumed) {
      _checkPremium();
    }
  }

  /// Шаг 1 (выбор тарифа на нашем хосте) — внутри WebView.
  /// Шаг 2 (страница оплаты wata.pro и любые банковские схемы) — во внешнем
  /// браузере.
  FutureOr<NavigationDecision> _handleNavigation(NavigationRequest request) {
    final uri = Uri.tryParse(request.url);
    if (uri == null) return NavigationDecision.prevent;

    final isHttp = uri.isScheme('https') || uri.isScheme('http');

    // Наш сайт (выбор тарифа, страницы успеха/ошибки) — остаёмся в WebView.
    if (isHttp && uri.host == _inAppHost) {
      return NavigationDecision.navigate;
    }

    // Всё остальное: платёжная форма payment.wata.pro/pay-form/<ID>,
    // редиректы эквайринга и deep-link'и банков (bank100000000111:// и т.п.)
    // — отдаём системе.
    _openExternally(uri);
    return NavigationDecision.prevent;
  }

  Future<void> _openExternally(Uri uri) async {
    try {
      final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (opened && mounted) {
        setState(() => _externalOpened = true);
      }
    } catch (_) {
      // Нечем открыть схему — молча игнорируем, пользователь может выбрать
      // другой способ оплаты на странице.
    }
  }

  void _startPolling() {
    final deadline = DateTime.now().add(_timeout);
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(_pollInterval, (timer) async {
      if (!mounted) {
        timer.cancel();
        return;
      }
      if (DateTime.now().isAfter(deadline)) {
        timer.cancel();
        return;
      }
      await _checkPremium();
    });
  }

  Future<void> _checkPremium() async {
    if (_closing || !mounted || _refreshInFlight) return;
    _refreshInFlight = true;

    try {
      await context.read<AuthProvider>().refreshUser();
      if (!mounted || _closing) return;
      if (context.read<AuthProvider>().isPremium) {
        _closing = true;
        _pollTimer?.cancel();
        Navigator.pop(context, true); // true = premium activated
      }
    } catch (_) {
      // Следующий тик поллинга или возврат в приложение повторит проверку.
    } finally {
      _refreshInFlight = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      appBar: AppBar(
        title: const Text('Оплата'),
        backgroundColor: Colors.transparent,
      ),
      body: SafeArea(
        child: Stack(
          children: [
            if (!_rendererCrashed) WebViewWidget(controller: _controller),
            if (_pageLoading && !_rendererCrashed)
              const Center(child: CircularProgressIndicator()),
            if (_rendererCrashed)
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.error_outline_rounded,
                        size: 48, color: AppColors.error),
                    const SizedBox(height: 12),
                    const Text(
                      'Страница оплаты не загрузилась',
                      style: TextStyle(
                        fontSize: 16,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      onPressed: () {
                        setState(() {
                          _rendererCrashed = false;
                          _pageLoading = true;
                        });
                        _controller.reload();
                      },
                      icon: const Icon(Icons.refresh_rounded),
                      label: const Text('Перезагрузить'),
                      style: FilledButton.styleFrom(
                        backgroundColor: AppColors.primaryGreen,
                        foregroundColor: Colors.black,
                      ),
                    ),
                  ],
                ),
              ),
            if (_externalOpened && !_pageLoading && !_rendererCrashed)
              Positioned(
                left: 16,
                right: 16,
                bottom: 16,
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(
                    color: AppColors.darkBackground.withOpacity(0.92),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: AppColors.primaryGreen.withOpacity(0.35),
                    ),
                  ),
                  child: Row(
                    children: const [
                      SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                      SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          'Оплата открыта в браузере. После оплаты вернитесь — '
                          'подписка активируется автоматически.',
                          style: TextStyle(
                            fontSize: 13,
                            height: 1.4,
                            color: AppColors.textSecondary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
