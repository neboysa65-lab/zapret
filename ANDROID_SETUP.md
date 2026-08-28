# Запуск приложения на Android эмуляторе

## Способ 1: Через Android Studio (Рекомендуется)

### Шаг 1: Запуск эмулятора
1. Откройте **Android Studio**
2. В главном меню выберите **Tools** → **Device Manager** (или нажмите на иконку телефона в правом верхнем углу)
3. В списке устройств найдите **Medium Phone API 35**
4. Нажмите на кнопку **▶️ Play** рядом с устройством
5. Дождитесь полной загрузки эмулятора (появится главный экран Android)

### Шаг 2: Проверка подключения
Откройте терминал и выполните:
```bash
cd /Volumes/ssd\ web/mobil/vpn/vpn_subscription_app
flutter devices
```

Вы должны увидеть что-то вроде:
```
Found 3 connected devices:
  sdk gphone64 arm64 (mobile) • emulator-5554 • android-arm64 • Android 14 (API 35)
  macOS (desktop)             • macos         • darwin-arm64  • macOS 26.3
  Chrome (web)                • chrome        • web-javascript • Google Chrome
```

### Шаг 3: Запуск приложения
```bash
flutter run -d emulator-5554
```

Или просто:
```bash
flutter run
```
И выберите Android эмулятор из списка.

---

## Способ 2: Через командную строку

### Шаг 1: Найти путь к emulator
```bash
which emulator
# Или
~/Library/Android/sdk/emulator/emulator
```

### Шаг 2: Запустить эмулятор вручную
```bash
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_35
```

### Шаг 3: Проверить подключение
```bash
flutter devices
```

### Шаг 4: Запустить приложение
```bash
cd /Volumes/ssd\ web/mobil/vpn/vpn_subscription_app
flutter run
```

---

## Способ 3: Создать новый эмулятор (если нужно)

### Через Android Studio:
1. **Tools** → **Device Manager**
2. Нажмите **Create Device**
3. Выберите **Phone** → **Pixel 7** (или любой другой)
4. Нажмите **Next**
5. Выберите **API Level 35** (или новее)
6. Нажмите **Next** → **Finish**
7. Запустите созданный эмулятор

---

## Возможные проблемы и решения

### Проблема 1: Эмулятор не запускается
**Решение:**
1. Откройте Android Studio
2. **Tools** → **SDK Manager**
3. Во вкладке **SDK Tools** убедитесь, что установлены:
   - ✅ Android Emulator
   - ✅ Android SDK Platform-Tools
   - ✅ Intel x86 Emulator Accelerator (HAXM) - для Intel Mac
   - ✅ Android Emulator hypervisor driver - для Apple Silicon

### Проблема 2: Flutter не видит эмулятор
**Решение:**
```bash
# Перезапустите adb
adb kill-server
adb start-server

# Проверьте устройства
adb devices
flutter devices
```

### Проблема 3: Ошибка при сборке
**Решение:**
```bash
# Очистите кэш
flutter clean
flutter pub get

# Попробуйте снова
flutter run
```

---

## Быстрый старт (если эмулятор уже запущен)

```bash
# Перейдите в папку проекта
cd /Volumes/ssd\ web/mobil/vpn/vpn_subscription_app

# Проверьте устройства
flutter devices

# Запустите приложение
flutter run
```

---

## Полезные команды

```bash
# Список всех эмуляторов
flutter emulators

# Запуск конкретного эмулятора
flutter emulators --launch Medium_Phone_API_35

# Список подключенных устройств
flutter devices

# Запуск на конкретном устройстве
flutter run -d emulator-5554

# Горячая перезагрузка (когда приложение запущено)
# Нажмите 'r' в терминале

# Полный рестарт
# Нажмите 'R' в терминале

# Остановить приложение
# Нажмите 'q' в терминале
```

---

## Что делать дальше?

После успешного запуска на Android:
1. Проверьте, как выглядит дизайн на мобильном устройстве
2. Протестируйте анимации и переходы
3. Проверьте работу кнопки подключения
4. Попробуйте выбрать разные серверы

**Примечание:** VPN функционал на эмуляторе может работать не полностью, так как требуются специальные разрешения. Для полного тестирования лучше использовать реальное устройство.
