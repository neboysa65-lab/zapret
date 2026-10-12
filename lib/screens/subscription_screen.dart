import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config/api_config.dart';
import '../models/tariff_model.dart';
import '../providers/auth_provider.dart';
import '../services/user_api_service.dart';
import 'pay_web_screen.dart';

class SubscriptionScreen extends StatefulWidget {
  const SubscriptionScreen({super.key});
  @override
  State<SubscriptionScreen> createState() => _SubscriptionScreenState();
}

class _SubscriptionScreenState extends State<SubscriptionScreen> {
  List<TariffModel> _tariffs = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadTariffs();
  }

  Future<void> _loadTariffs() async {
    try {
      final tariffs = await UserApiService.getTariffs();
      setState(() { _tariffs = tariffs; _loading = false; });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  Future<void> _buy(TariffModel tariff) async {
    final auth = context.read<AuthProvider>();
    final userId = auth.user?.userId ?? '';
    if (userId.isEmpty) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось определить аккаунт. Войдите заново.')),
      );
      return;
    }
    final url = '${ApiConfig.baseUrl}/pay?uid=$userId&tariff_id=${tariff.id}';

    // Без Remote Config baseUrl пуст — открывать нечего (иначе WebView
    // покажет системный экран ошибки с адресом).
    if (Uri.tryParse(url)?.host.isEmpty ?? true) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Нет подключения. Проверьте интернет и попробуйте позже.')),
      );
      return;
    }

    // Выбор тарифа — в WebView внутри приложения; сама оплата (wata.pro)
    // откроется во внешнем браузере. Поллинг премиума внутри PayWebScreen.
    final activated = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => PayWebScreen(url: url)),
    );

    if (activated == true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Premium активирован!')),
      );
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Подписка')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Expanded(
                  child: _tariffs.isEmpty
                      ? const Center(child: Text('Тарифы недоступны'))
                      : ListView.builder(
                          padding: const EdgeInsets.all(16),
                          itemCount: _tariffs.length,
                          itemBuilder: (context, i) => _TariffCard(
                            tariff: _tariffs[i],
                            onBuy: () => _buy(_tariffs[i]),
                          ),
                        ),
                ),
              ],
            ),
    );
  }
}

class _TariffCard extends StatelessWidget {
  final TariffModel tariff;
  final VoidCallback? onBuy;
  const _TariffCard({required this.tariff, this.onBuy});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(tariff.name, style: Theme.of(context).textTheme.titleMedium),
                  Text('${tariff.durationDays} дней',
                      style: const TextStyle(color: Colors.grey, fontSize: 13)),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(tariff.formattedPrice,
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                FilledButton(
                  onPressed: onBuy,
                  child: const Text('Купить'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
