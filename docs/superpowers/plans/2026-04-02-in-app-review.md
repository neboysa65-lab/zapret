# In-App Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google Play In-App Review — a "Rate Us" item in Settings and an automatic prompt shown after VPN disconnect (max once per 5 days, skipped if user already rated via Settings).

**Architecture:** A new `RatingService` encapsulates all review logic and persistence via `SharedPreferences`. A global `navigatorKey` is added to `main.dart` so `RatingService` can obtain a `BuildContext` without threading it through `AppProvider`. Settings screen gets a new ListTile; AppProvider's disconnect handler calls `RatingService`.

**Tech Stack:** Flutter, `in_app_review ^2.0.9`, `shared_preferences ^2.2.2` (already in pubspec)

---

## Files

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `lib/services/rating_service.dart` | All review logic + SharedPreferences persistence |
| Modify | `lib/providers/app_provider.dart` | Call `RatingService.maybeShowReview()` on disconnect |
| Modify | `lib/screens/settings_screen.dart` | Add "Rate Us" ListTile |
| Modify | `lib/l10n/app_en.arb` | Add `rateUs` key |
| Modify | `lib/l10n/app_ru.arb` | Add `rateUs` key |
| Modify | `pubspec.yaml` | Add `in_app_review` dependency |

---

## Task 1: Add `in_app_review` dependency

**Files:**
- Modify: `pubspec.yaml`

- [ ] **Step 1: Add dependency**

In `pubspec.yaml`, under `dependencies:`, after `package_info_plus: ^9.0.0`, add:

```yaml
  in_app_review: ^2.0.9
```

- [ ] **Step 2: Install**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter pub get
```

Expected: `Got dependencies!` with no errors.

- [ ] **Step 3: Commit**

```bash
git add pubspec.yaml pubspec.lock
git commit -m "chore: add in_app_review dependency"
```

---

## Task 2: Add localization strings

**Files:**
- Modify: `lib/l10n/app_en.arb`
- Modify: `lib/l10n/app_ru.arb`

- [ ] **Step 1: Add English string**

In `lib/l10n/app_en.arb`, add before the closing `}`:

```json
    "rateUs": "Rate Us"
```

The file ends with `"version": "Version",` then `"privacyPolicyContent": "..."`. Add the new key after `"version"` line:

```json
    "version": "Version",
    "rateUs": "Rate Us",
    "privacyPolicyContent": "..."
```

- [ ] **Step 2: Add Russian string**

In `lib/l10n/app_ru.arb`, add the same key after `"version"` entry:

```json
    "version": "Версия",
    "rateUs": "Оцените нас",
    "privacyPolicyContent": "..."
```

- [ ] **Step 3: Regenerate localizations**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter gen-l10n
```

Expected: No errors. File `lib/l10n/generated/app_localizations.dart` updated with `rateUs` getter.

- [ ] **Step 4: Commit**

```bash
git add lib/l10n/app_en.arb lib/l10n/app_ru.arb lib/l10n/generated/
git commit -m "feat: add rateUs localization string"
```

---

## Task 3: Create `RatingService`

**Files:**
- Create: `lib/services/rating_service.dart`

- [ ] **Step 1: Create the service file**

Create `lib/services/rating_service.dart` with the following content:

```dart
import 'package:flutter/material.dart';
import 'package:in_app_review/in_app_review.dart';
import 'package:shared_preferences/shared_preferences.dart';

class RatingService {
  static const String _keyLastPromptDate = 'rating_last_prompt_date';
  static const String _keyHasRated = 'rating_has_rated';
  static const int _minDaysBetweenPrompts = 5;

  final InAppReview _inAppReview = InAppReview.instance;

  /// Called automatically on VPN disconnect.
  /// Shows review prompt if 5+ days have passed since last prompt
  /// and the user has not already rated via Settings.
  Future<void> maybeShowReview() async {
    try {
      final prefs = await SharedPreferences.getInstance();

      final bool hasRated = prefs.getBool(_keyHasRated) ?? false;
      if (hasRated) return;

      final String? lastDateStr = prefs.getString(_keyLastPromptDate);
      if (lastDateStr != null) {
        final DateTime lastDate = DateTime.parse(lastDateStr);
        final int daysSince = DateTime.now().difference(lastDate).inDays;
        if (daysSince < _minDaysBetweenPrompts) return;
      }

      final bool available = await _inAppReview.isAvailable();
      if (!available) return;

      await _inAppReview.requestReview();
      await prefs.setString(
        _keyLastPromptDate,
        DateTime.now().toIso8601String(),
      );
    } catch (_) {
      // Silently ignore — review prompt is non-critical
    }
  }

  /// Called from Settings "Rate Us" tap.
  /// Always attempts to show review and marks user as having rated.
  Future<void> showReviewFromSettings() async {
    try {
      final bool available = await _inAppReview.isAvailable();
      if (!available) return;

      await _inAppReview.requestReview();

      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_keyHasRated, true);
    } catch (_) {
      // Silently ignore
    }
  }
}
```

- [ ] **Step 2: Verify analysis**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter analyze lib/services/rating_service.dart
```

Expected: `No issues found!`

- [ ] **Step 3: Commit**

```bash
git add lib/services/rating_service.dart
git commit -m "feat: add RatingService with 5-day cooldown and has-rated flag"
```

---

## Task 4: Call `maybeShowReview` on VPN disconnect

**Files:**
- Modify: `lib/providers/app_provider.dart`

- [ ] **Step 1: Import RatingService**

At the top of `lib/providers/app_provider.dart`, add the import after the existing service imports:

```dart
import '../services/rating_service.dart';
```

- [ ] **Step 2: Call maybeShowReview on disconnect**

Find the status stream listener in `app_provider.dart` (around line 162). The `disconnected` branch currently looks like:

```dart
} else if (status == VpnStatus.disconnected) {
  _stopTimer();
  _connectionDuration = Duration.zero;
  _downloadSpeed = "0.0 B/s";
  _uploadSpeed = "0.0 B/s";
  _downloadSpeedBytes = 0;
  _uploadSpeedBytes = 0;
}
```

Add the `RatingService` call at the end of that branch:

```dart
} else if (status == VpnStatus.disconnected) {
  _stopTimer();
  _connectionDuration = Duration.zero;
  _downloadSpeed = "0.0 B/s";
  _uploadSpeed = "0.0 B/s";
  _downloadSpeedBytes = 0;
  _uploadSpeedBytes = 0;
  RatingService().maybeShowReview();
}
```

- [ ] **Step 3: Verify analysis**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter analyze lib/providers/app_provider.dart
```

Expected: `No issues found!`

- [ ] **Step 4: Commit**

```bash
git add lib/providers/app_provider.dart
git commit -m "feat: trigger RatingService on VPN disconnect"
```

---

## Task 5: Add "Rate Us" ListTile to Settings screen

**Files:**
- Modify: `lib/screens/settings_screen.dart`

- [ ] **Step 1: Import RatingService**

At the top of `lib/screens/settings_screen.dart`, add import after existing service imports:

```dart
import '../services/rating_service.dart';
```

- [ ] **Step 2: Add Rate Us ListTile**

In the `build()` method, find the Telegram ListTile item (which has `supportTelegram` label). Add the new Rate Us tile **after** the Telegram tile and **before** the Privacy Policy tile.

The current order is: Split Tunneling → Telegram → Privacy Policy → Version.
New order: Split Tunneling → Telegram → **Rate Us** → Privacy Policy → Version.

Add between Telegram and Privacy Policy tiles:

```dart
const Divider(height: 1, color: Color(0xFF2A2D35)),
ListTile(
  leading: Icon(
    Icons.star_outline_rounded,
    color: AppColors.primaryGreen,
  ),
  title: Text(
    l10n.rateUs,
    style: const TextStyle(
      color: Colors.white,
      fontSize: 16,
    ),
  ),
  trailing: const Icon(
    Icons.arrow_forward_ios,
    color: Color(0xFF6B7280),
    size: 16,
  ),
  onTap: () => RatingService().showReviewFromSettings(),
),
```

- [ ] **Step 3: Verify analysis**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter analyze lib/screens/settings_screen.dart
```

Expected: `No issues found!`

- [ ] **Step 4: Commit**

```bash
git add lib/screens/settings_screen.dart
git commit -m "feat: add Rate Us item to Settings screen"
```

---

## Task 6: Final verification

- [ ] **Step 1: Full analyze**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter analyze
```

Expected: `No issues found!`

- [ ] **Step 2: Build APK to verify compilation**

```bash
cd "/Volumes/WORK/mobil 2/vpn/vpn_subscription_app"
flutter build apk --release
```

Expected: `✓ Built build/app/outputs/flutter-apk/app-release.apk`

- [ ] **Step 3: Manual test checklist**

Install the APK on a device and verify:
1. Settings screen shows "Оцените нас" / "Rate Us" tile between Telegram and Privacy Policy
2. Tapping "Rate Us" in Settings triggers the native Google review dialog (or does nothing if not available on emulator)
3. After tapping "Rate Us" in Settings, check SharedPreferences has `rating_has_rated = true`
4. Connect VPN → Disconnect → verify `maybeShowReview` is called (on emulator the dialog won't appear but no crash)

> Note: Google's In-App Review API will not show the dialog on a development build or emulator — this is expected behavior. Test the settings tap to confirm no crash.
