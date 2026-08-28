# Flutter Direct Remnawave

Краткая документация по варианту, когда Flutter ходит в Remnawave напрямую, без отдельного backend.

## Важно

Такой вариант подходит только если вы понимаете риск:

- `API token` окажется внутри мобильного приложения
- его можно будет достать из APK/IPA
- любой, кто получит токен, сможет ходить в вашу панель

Для публичного production VPN-клиента это плохая практика.

Если все равно хотите делать напрямую, лучше:

- использовать отдельный сильно ограниченный token
- не давать token с лишними правами
- понимать, что это компромисс по безопасности

## Рабочие значения для этого проекта

- `panelBaseUrl`: `https://panel.gw-vpn.click`
- `remnawaveUserUuid`: `676aaee4-2ce4-4e89-a290-c9a7efa5279f`
- `subscriptionUrl`: `https://sub.gw-vpn.click/RJjLtQYW45003BEJ`
- технический user в панели: `api_vless-vpn`

Если нужна именно прямая интеграция во Flutter без отдельного backend, то приложение ходит в:

- `GET https://panel.gw-vpn.click/api/bandwidth-stats/nodes/realtime`
- при необходимости `GET https://panel.gw-vpn.click/api/system/nodes/metrics`

## Что нужно во Flutter

Во Flutter вам нужны:

- `panelBaseUrl`
  Например: `https://panel.gw-vpn.click`

- `apiToken`
  Ваш `Bearer` token

- список ваших серверов
  Либо хранить в приложении, либо отдельно тянуть subscription

## Какие запросы делать

### 1. Live-нагрузка нод

Основной endpoint:

```http
GET /api/bandwidth-stats/nodes/realtime
Authorization: Bearer <API_TOKEN>
```

Он возвращает:

- `nodeUuid`
- `nodeName`
- `countryCode`
- `downloadBytes`
- `uploadBytes`
- `totalBytes`
- `downloadSpeedBps`
- `uploadSpeedBps`
- `totalSpeedBps`

Именно этот endpoint нужен для live-нагрузки.

### 2. Список нод и общая мета

Дополнительный endpoint:

```http
GET /api/system/nodes/metrics
Authorization: Bearer <API_TOKEN>
```

Он возвращает:

- `nodeUuid`
- `nodeName`
- `countryEmoji`
- `providerName`
- `usersOnline`

Для вашей схемы `usersOnline` не стоит использовать как главный сигнал нагрузки,
но `countryEmoji` и `nodeName` удобно брать отсюда.

### 3. Если хотите историю по user

Необязательно для live UI:

```http
GET /api/bandwidth-stats/users/:userUuid?start=YYYY-MM-DD&end=YYYY-MM-DD
Authorization: Bearer <API_TOKEN>
```

Но если задача именно live-нагрузка, этот endpoint можно не использовать.

## Как считать нагрузку

Если идете напрямую из Flutter, лучше считать только по realtime:

1. Берете все ноды из `/api/bandwidth-stats/nodes/realtime`
2. Находите максимальный `totalSpeedBps` среди нужных вам нод
3. Для каждой ноды считаете:

```text
liveSpeedScore10 = round((node.totalSpeedBps / maxTotalSpeedBps) * 10)
loadScore10 = liveSpeedScore10
```

4. Потом присваиваете статус:

```text
0..2  -> low
3..5  -> medium
6..8  -> high
9..10 -> overloaded
```

5. Для UI можно считать:

```text
liveSpeedMbps = totalSpeedBps / 1_000_000
```

## Как связывать с вашими серверами

Есть 2 варианта.

### Вариант 1. По `nodeName`

Если в приложении у сервера есть имя типа:

- `VLESSAPP-RU01`
- `VLESSAPP-NL01`

тогда просто мапите по `nodeName`.

### Вариант 2. По `host`

Если у вас есть список `vless://` серверов и нужен `host`,
тогда придется дополнительно:

- либо хранить у себя маппинг `host -> nodeName`
- либо парсить subscription во Flutter

Самый простой вариант:

- хранить локально таблицу `host -> nodeName`

Пример:

```dart
const hostToNodeName = {
  'vleesvpn-app-ru01.gw-vpn.click': 'VLESSAPP-RU01',
  'vleesvpn-app-nl01.gw-vpn.click': 'VLESSAPP-NL01',
  'vleesvpn-app-nl02.gw-vpn.click': 'VLESSAPP-NL02',
  'vleesvpn-app-ru02.gw-vpn.click': 'VLESSAPP-RU02',
  'vleesvpn-app-uk01.gw-vpn.click': 'VLESSAPP-UK01',
  'vleesvpn-app-pl01.gw-vpn.click': 'VLESSAPP-PL01',
  'vleesvpn-app-tr01.gw-vpn.click': 'VLESSAPP-TR01',
};
```

Эти `host` соответствуют подписке:

```text
https://sub.gw-vpn.click/RJjLtQYW45003BEJ
```

## Минимальная модель Flutter

```dart
class RemnawaveRealtimeNode {
  final String nodeUuid;
  final String nodeName;
  final String countryCode;
  final int downloadSpeedBps;
  final int uploadSpeedBps;
  final int totalSpeedBps;

  RemnawaveRealtimeNode({
    required this.nodeUuid,
    required this.nodeName,
    required this.countryCode,
    required this.downloadSpeedBps,
    required this.uploadSpeedBps,
    required this.totalSpeedBps,
  });

  factory RemnawaveRealtimeNode.fromJson(Map<String, dynamic> json) {
    return RemnawaveRealtimeNode(
      nodeUuid: json['nodeUuid'] as String,
      nodeName: json['nodeName'] as String,
      countryCode: json['countryCode'] as String,
      downloadSpeedBps: json['downloadSpeedBps'] as int,
      uploadSpeedBps: json['uploadSpeedBps'] as int,
      totalSpeedBps: json['totalSpeedBps'] as int,
    );
  }
}
```

## Ответ Remnawave

```dart
class RemnawaveRealtimeResponse {
  final List<RemnawaveRealtimeNode> response;

  RemnawaveRealtimeResponse({required this.response});

  factory RemnawaveRealtimeResponse.fromJson(Map<String, dynamic> json) {
    final items = json['response'] as List<dynamic>;
    return RemnawaveRealtimeResponse(
      response: items
          .map((item) => RemnawaveRealtimeNode.fromJson(item as Map<String, dynamic>))
          .toList(),
    );
  }
}
```

## Итоговая модель для UI

```dart
class VpnServerLoad {
  final String nodeName;
  final String countryCode;
  final double liveSpeedMbps;
  final int loadScore10;
  final String loadStatus;

  VpnServerLoad({
    required this.nodeName,
    required this.countryCode,
    required this.liveSpeedMbps,
    required this.loadScore10,
    required this.loadStatus,
  });
}
```

## Сервис Remnawave во Flutter

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;

class RemnawaveApiService {
  final String baseUrl;
  final String apiToken;

  RemnawaveApiService({
    required this.baseUrl,
    required this.apiToken,
  });

  Map<String, String> get _headers => {
        'Authorization': 'Bearer $apiToken',
        'Accept': 'application/json',
      };

  Future<List<RemnawaveRealtimeNode>> fetchRealtimeNodes() async {
    final uri = Uri.parse('$baseUrl/api/bandwidth-stats/nodes/realtime');
    final response = await http.get(uri, headers: _headers);

    if (response.statusCode != 200) {
      throw Exception('Realtime request failed: ${response.statusCode}');
    }

    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final parsed = RemnawaveRealtimeResponse.fromJson(json);
    return parsed.response;
  }
}
```

## Расчет нагрузки во Flutter

```dart
List<VpnServerLoad> buildServerLoads(List<RemnawaveRealtimeNode> nodes) {
  final maxTotalSpeedBps = nodes.isEmpty
      ? 0
      : nodes.map((e) => e.totalSpeedBps).reduce((a, b) => a > b ? a : b);

  int calcScore(int totalSpeedBps) {
    if (maxTotalSpeedBps <= 0) return 0;
    return ((totalSpeedBps / maxTotalSpeedBps) * 10).round().clamp(0, 10);
  }

  String calcStatus(int score) {
    if (score <= 2) return 'low';
    if (score <= 5) return 'medium';
    if (score <= 8) return 'high';
    return 'overloaded';
  }

  return nodes.map((node) {
    final score = calcScore(node.totalSpeedBps);
    return VpnServerLoad(
      nodeName: node.nodeName,
      countryCode: node.countryCode,
      liveSpeedMbps: node.totalSpeedBps / 1000000.0,
      loadScore10: score,
      loadStatus: calcStatus(score),
    );
  }).toList();
}
```

## Обновление данных

Для live UI достаточно опрашивать API:

- раз в `15-30` секунд

Например:

- экран открылся -> загрузили
- таймер каждые 20 секунд -> обновили
- экран закрылся -> таймер остановили

## Пример использования

```dart
final api = RemnawaveApiService(
  baseUrl: 'https://panel.gw-vpn.click',
  apiToken: 'YOUR_API_TOKEN',
);

final realtimeNodes = await api.fetchRealtimeNodes();
final loads = buildServerLoads(realtimeNodes);
```

## Что показывать пользователю

Минимально:

- название сервера
- `liveSpeedMbps`
- `loadStatus`

Если хотите подробнее:

- `loadScore10`

Пример:

- `RU01` — `107.8 Mbps` — `overloaded`
- `NL01` — `74.4 Mbps` — `high`
- `NL02` — `27.1 Mbps` — `medium`

## Что лучше не делать

- не хранить token в открытом виде в коде
- не пушить token в git
- не использовать админский token с широкими правами, если можно сделать отдельный

## Практический вывод

Если вы идете без отдельного backend, то во Flutter вам достаточно:

1. Дернуть `GET /api/bandwidth-stats/nodes/realtime`
2. Посчитать `loadScore10` по `totalSpeedBps`
3. Показать `loadStatus`

Это самый прямой вариант реализации.
