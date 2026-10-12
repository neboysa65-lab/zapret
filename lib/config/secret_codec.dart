import 'dart:convert';
import 'dart:typed_data';

/// Кодирование URL, чтобы в билде и в JSON Remote Config не было открытых
/// строк https://... — именно их ищут DPI-парсеры и реверсеры.
///
/// Формат: gwenc1: + base64( reverse( ((plain[i] XOR key[i mod 16]) + 0x5D) mod 256 ) )
/// [decode] вызывается непосредственно перед использованием значения.
class SecretCodec {
  SecretCodec._();

  static const String prefix = 'gwenc1:';
  static const int _offset = 0x5D;
  static const List<int> _key = <int>[
    0x37, 0x5B, 0x71, 0x2E, 0x49, 0x6D, 0x23, 0x58,
    0x0F, 0x66, 0x3C, 0x7A, 0x11, 0x44, 0x69, 0x05,
  ];

  /// Признак закодированного значения (с учётом пробелов вокруг).
  static bool isEncoded(String? raw) =>
      raw?.trim().startsWith(prefix) ?? false;

  /// Расшифровка. Значения без префикса (plaintext) и битые данные
  /// возвращаются как есть — приложение никогда не остаётся без URL.
  static String decode(String? raw) {
    final value = raw?.trim() ?? '';
    if (!value.startsWith(prefix)) return value;
    try {
      final data = base64.decode(value.substring(prefix.length));
      final out = Uint8List(data.length);
      for (var i = 0; i < data.length; i++) {
        final shifted = (data[data.length - 1 - i] - _offset) & 0xFF;
        out[i] = (shifted ^ _key[i % _key.length]) & 0xFF;
      }
      return utf8.decode(out);
    } catch (_) {
      return value;
    }
  }

  /// Для генерации новых закодированных значений (консоль Firebase).
  static String encode(String plain) {
    final data = utf8.encode(plain);
    final out = Uint8List(data.length);
    for (var i = 0; i < data.length; i++) {
      final shifted = ((data[i] ^ _key[i % _key.length]) + _offset) & 0xFF;
      out[data.length - 1 - i] = shifted;
    }
    return prefix + base64.encode(out);
  }
}
