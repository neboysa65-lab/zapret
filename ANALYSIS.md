# 🔍 Анализ текущего состояния интеграции API и круговых индикаторов

## 📊 Что было сделано (по словам другого ИИ)

1. ✅ Создан `metrics_service.dart` для HTTP-запросов к панели
2. ✅ Добавлен виджет `CircularProgressIndicator` в `server_list_screen.dart`
3. ✅ Настроена параллельная загрузка через `Future.wait` в `app_provider.dart`
4. ✅ Исправлена ошибка 401 Unauthorized (добавлен JWT токен)
5. ✅ Исправлен парсинг JSON (`{"response": {"nodes": [...]}}`)
6. ✅ Добавлена фильтрация только для нод начинающихся с `VLESSAPP-`
7. ✅ Реализован "умный" расчет процентов относительно максимальной загрузки

---

## 🔬 Детальный анализ кода

### 1. MetricsService (`lib/services/metrics_service.dart`)

**Что делает:**
- Отправляет GET запрос на `https://panel.gw-vpn.click/api/system/nodes/metrics`
- Использует JWT токен для авторизации
- Парсит JSON ответ и возвращает `Map<String, int>` (имя ноды -> количество юзеров)

**Потенциальные проблемы:**
- ⚠️ JWT токен захардкожен в коде (безопасность)
- ⚠️ Нет логирования успешных запросов (только ошибки)
- ⚠️ Нет retry логики при ошибках

### 2. AppProvider (`lib/providers/app_provider.dart`)

#### Нормализация имен серверов (строки 84-98)

```dart
String _normalizeServerName(String name) {
  // 1. Убираем всё, кроме букв и цифр, переводим в нижний регистр
  String cleaned = name.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toLowerCase();
  
  // 2. Убираем лишнее слово "app"
  cleaned = cleaned.replaceAll('app', '');
  cleaned = cleaned.replaceAll('vless', '');
  
  // 3. Вырезаем все ведущие нули у цифр на конце строки
  cleaned = cleaned.replaceAllMapped(RegExp(r'0+([1-9]+)$'), (match) => match.group(1)!); 
  
  return cleaned;
}
```

**Примеры работы:**
- `"VLESSAPP 🇷🇺 RU 1"` → `"vlessappru1"` → `"vlessru1"` → `"ru1"` ✅
- `"VLESSAPP-RU01"` → `"vlessappru01"` → `"vlessru01"` → `"ru01"` → `"ru1"` ✅
- `"VLESSAPP-DE02"` → `"vlessappde02"` → `"vlessde02"` → `"de02"` → `"de2"` ✅

**Потенциальная проблема:**
- ⚠️ Регулярное выражение `RegExp(r'0+([1-9]+)$')` работает ТОЛЬКО если строка заканчивается цифрами
- ⚠️ Если после нормализации строка не заканчивается цифрами, ведущие нули НЕ удалятся
- Пример: `"VLESSAPP-TEST"` → `"test"` (нет цифр, проблем нет)
- Пример: `"VLESSAPP-01TEST"` → `"01test"` (не заканчивается цифрами, нули останутся!)

#### Фильтрация нод (строки 167-174)

```dart
rawMetrics.forEach((nodeName, online) {
  // Засчитываем пользователей ТОЛЬКО если оригинальное имя ноды начинается с "VLESSAPP-"
  if (nodeName.toUpperCase().startsWith('VLESSAPP-')) {
    String normalizedNode = _normalizeServerName(nodeName);
    _serverMetrics[normalizedNode] = (_serverMetrics[normalizedNode] ?? 0) + online;
    _totalOnlineUsers += online;
  }
});
```

**Что делает:**
- Фильтрует ноды которые начинаются с `VLESSAPP-` (регистронезависимо)
- Нормализует имя ноды
- Суммирует юзеров если несколько нод нормализуются в одно имя

**Потенциальная проблема:**
- ⚠️ Если в API есть ноды которые НЕ начинаются с `VLESSAPP-`, они будут проигнорированы
- ⚠️ Нет логирования отфильтрованных нод (сложно дебажить)

#### Расчет процентов загрузки (строки 56-77)

```dart
double getServerLoadPercentage(VlessConfig config) {
  if (_serverMetrics.isEmpty) return 0.0;
  
  int currentOnline = getServerOnlineUsers(config);
  if (currentOnline == 0) return 0.0;

  // 1. Ищем самый загруженный сервер среди всех доступных
  int maxOnline = 1;
  for (var s in _servers) {
    final sOnline = getServerMetricsByConfig(s);
    if (sOnline > maxOnline) maxOnline = sOnline;
  }
  
  // 2. Сравниваем текущий сервер с самым загруженным
  int comparativeCapacity = math.max(10, maxOnline);

  double percentage = (currentOnline / comparativeCapacity) * 100;
  return percentage.clamp(0.0, 100.0);
}
```

**Что делает:**
- Находит сервер с максимальным количеством юзеров
- Использует его как 100% (с минимальным порогом 10 юзеров)
- Вычисляет процент текущего сервера относительно максимального

**Потенциальные проблемы:**
- ⚠️ Если все серверы имеют 0 юзеров, `maxOnline` будет 1, и все проценты будут 0%
- ⚠️ Минимальный порог 10 юзеров может быть слишком высоким или слишком низким
- ⚠️ Нет обновления метрик в реальном времени (только при загрузке серверов)

### 3. ServerListScreen (`lib/screens/server_list_screen.dart`)

#### Индикатор метрик (строки 388-442)

```dart
Widget _buildMetricsIndicator(AppProvider provider, VlessConfig server) {
  if (provider.isLoading) return const SizedBox();

  final loadPercentage = provider.getServerLoadPercentage(server);
  final usersOnline = provider.getServerOnlineUsers(server);

  Color indicatorColor;
  if (loadPercentage < 40) {
    indicatorColor = AppColors.primaryGreen;
  } else if (loadPercentage < 75) {
    indicatorColor = Colors.orange;
  } else {
    indicatorColor = AppColors.error;
  }

  return Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      SizedBox(
        width: 38,
        height: 38,
        child: Stack(
          fit: StackFit.expand,
          children: [
            CircularProgressIndicator(
              value: loadPercentage / 100,
              strokeWidth: 3,
              backgroundColor: Colors.white.withValues(alpha: 0.1),
              color: indicatorColor,
            ),
            Center(
              child: Text(
                '${loadPercentage.toInt()}%',
                style: TextStyle(
                  color: indicatorColor,
                  fontSize: 10,
                  fontWeight: FontWeight.bold,
                ),
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 2),
      Text(
        '$usersOnline usr',
        style: TextStyle(
          color: Colors.grey[400],
          fontSize: 9,
        ),
      ),
    ],
  );
}
```

**Что делает:**
- Показывает круговой индикатор с процентом загрузки
- Показывает количество юзеров под индикатором
- Меняет цвет в зависимости от загрузки (зеленый/оранжевый/красный)

**Потенциальные проблемы:**
- ⚠️ Нет анимации обновления процента
- ⚠️ Нет индикатора загрузки метрик (если API медленный)
- ⚠️ Нет возможности обновить метрики отдельно от списка серверов

---

## 🐛 Возможные причины "кривой работы"

### Проблема 1: Нулевые значения везде
**Симптомы:** Все индикаторы показывают "0 usr" и "0%"

**Возможные причины:**
1. API возвращает пустой массив нод
2. Имена нод в API не совпадают с именами в подписке после нормализации
3. Все ноды в API не начинаются с `VLESSAPP-`
4. Ошибка парсинга JSON (неправильная структура)

**Как проверить:**
- Запустить `compare2.dart` или `compare3.dart` для сравнения имен
- Проверить консоль на наличие логов нормализации

### Проблема 2: Неправильные проценты
**Симптомы:** Проценты не соответствуют реальной загрузке

**Возможные причины:**
1. Неправильная нормализация имен (ноды не матчатся)
2. Логика расчета процентов работает некорректно
3. Минимальный порог 10 юзеров слишком высокий/низкий

**Как проверить:**
- Проверить логи нормализации в консоли
- Сравнить нормализованные имена из подписки и API

### Проблема 3: Индикаторы не обновляются
**Симптомы:** Значения остаются старыми после изменений на сервере

**Возможные причины:**
1. Метрики загружаются только один раз при запуске
2. Нет периодического обновления
3. Нет pull-to-refresh для метрик

**Как исправить:**
- Добавить таймер для периодического обновления метрик
- Добавить pull-to-refresh для метрик

### Проблема 4: Некоторые серверы показывают 0 хотя на них сидят люди
**Симптомы:** Часть серверов показывает правильные значения, часть - нули

**Возможные причины:**
1. Неправильная нормализация для конкретных серверов
2. Эти серверы не начинаются с `VLESSAPP-` в API
3. Имена серверов в подписке и API слишком разные

**Как проверить:**
- Запустить `compare2.dart` чтобы увидеть все имена и их нормализацию

---

## 🔧 Рекомендации по улучшению

### 1. Добавить логирование для дебага
```dart
// В loadServers() после нормализации
print('=== МЕТРИКИ ИЗ API ===');
rawMetrics.forEach((nodeName, online) {
  print('  $nodeName: $online юзеров -> нормализовано: ${_normalizeServerName(nodeName)}');
});

print('=== СЕРВЕРЫ ИЗ ПОДПИСКИ ===');
for (var s in _servers) {
  print('  ${s.serverName} -> нормализовано: ${_normalizeServerName(s.serverName)}');
}
```

### 2. Улучшить нормализацию
```dart
String _normalizeServerName(String name) {
  String cleaned = name.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toLowerCase();
  cleaned = cleaned.replaceAll('app', '');
  cleaned = cleaned.replaceAll('vless', '');
  
  // Улучшенное удаление ведущих нулей
  // Удаляем нули после букв и перед цифрами
  cleaned = cleaned.replaceAllMapped(RegExp(r'([a-z])0+([1-9]+)'), (match) {
    return '${match.group(1)}${match.group(2)}';
  });
  
  // Также удаляем ведущие нули в начале строки
  cleaned = cleaned.replaceAll(RegExp(r'^0+'), '');
  
  return cleaned;
}
```

### 3. Добавить периодическое обновление метрик
```dart
// В AppProvider добавить
Timer? _metricsTimer;

void startMetricsRefresh() {
  _metricsTimer?.cancel();
  _metricsTimer = Timer.periodic(const Duration(seconds: 30), (timer) {
    _refreshMetrics();
  });
}

Future<void> _refreshMetrics() async {
  try {
    final rawMetrics = await _metricsService.fetchMetrics();
    // ... обновить _serverMetrics и _totalOnlineUsers
    notifyListeners();
  } catch (e) {
    print('Error refreshing metrics: $e');
  }
}
```

### 4. Добавить UI обратную связь
- Показывать индикатор загрузки пока метрики не загружены
- Показывать ошибку если API недоступен
- Добавить pull-to-refresh для метрик

### 5. Вынести токен в конфигурацию
```dart
// В config/api_config.dart
class ApiConfig {
  static const String metricsUrl = 'https://panel.gw-vpn.click/api/system/nodes/metrics';
  static const String metricsToken = 'YOUR_TOKEN_HERE';
}
```

---

## 📝 Итоговый вердикт

### Что работает хорошо:
✅ Базовая архитектура (Service -> Provider -> UI)
✅ Парсинг JSON ответа от API
✅ Фильтрация нод по префиксу `VLESSAPP-`
✅ Базовая логика нормализации
✅ UI компонент с круговым индикатором

### Что нужно исправить:
❌ Добавить детальное логирование для дебага
❌ Улучшить нормализацию имен серверов
❌ Добавить периодическое обновление метрик
❌ Добавить UI обратную связь (загрузка, ошибки)
❌ Вынести токен в конфигурацию
❌ Добавить обработку edge cases (все нули, пустой ответ API)

### Приоритеты исправлений:
1. 🔴 **Высокий:** Добавить логирование чтобы понять почему данные не матчатся
2. 🔴 **Высокий:** Проверить реальные имена серверов из подписки и API
3. 🟡 **Средний:** Улучшить нормализацию
4. 🟡 **Средний:** Добавить периодическое обновление
5. 🟢 **Низкий:** Улучшить UI обратную связь
6. 🟢 **Низкий:** Вынести токен в конфиг
