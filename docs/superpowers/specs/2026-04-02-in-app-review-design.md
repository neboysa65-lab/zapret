# In-App Review — Design Spec
Date: 2026-04-02

## Overview

Add Google Play In-App Review to the VPN app:
1. A "Rate Us" item in Settings that triggers the native review dialog
2. An automatic review prompt shown after VPN disconnect, at most once every 5 days, suppressed if the user already rated via Settings

---

## Architecture

### New: `RatingService` (`lib/services/rating_service.dart`)

Responsible for all review logic. Uses `SharedPreferences` for persistence.

**Storage keys:**
- `rating_last_prompt_date` (String, ISO-8601) — last time the prompt was shown automatically
- `rating_has_rated` (bool) — user tapped "Rate Us" in Settings

**Methods:**

```dart
// Called on VPN disconnect. Shows prompt if conditions are met.
Future<void> maybeShowReview(BuildContext context) async

// Called from Settings. Always triggers review, sets has_rated = true.
Future<void> showReviewFromSettings(BuildContext context) async
```

**`maybeShowReview` logic:**
1. If `has_rated == true` → return (do nothing)
2. If `last_prompt_date` exists and fewer than 5 days have passed → return
3. Otherwise: call `InAppReview.instance.requestReview()`, save today as `last_prompt_date`

**`showReviewFromSettings` logic:**
1. Call `InAppReview.instance.requestReview()`
2. Set `has_rated = true`

> Note: Google Play In-App Review API does not confirm whether the user actually submitted a rating. `has_rated` tracks that the user intentionally opened the review flow from Settings — this is sufficient to suppress future auto-prompts.

---

## Settings Screen Changes (`lib/screens/settings_screen.dart`)

Add a new `ListTile` between the Telegram and Privacy Policy items:

- Icon: `Icons.star_outline_rounded` (green accent `#A3FF00`)
- Label: localized string `rateUs` (EN: "Rate Us", RU: "Оцените нас")
- On tap: call `RatingService().showReviewFromSettings(context)`

---

## AppProvider Changes (`lib/providers/app_provider.dart`)

In the `_handleVpnStatusChange` method (where status transitions to `VpnStatus.disconnected`), add:

```dart
if (context != null) {
  RatingService().maybeShowReview(context!);
}
```

AppProvider needs to hold a `BuildContext` reference or receive it via a callback. The cleanest approach is to pass context from `HomeScreen` when calling `disconnectVpn()` — or use a Navigator key registered in `main.dart`.

Preferred: use the global `navigatorKey` already available (or add one) so `RatingService` can get context without modifying AppProvider's API.

---

## Dependencies

Add to `pubspec.yaml`:
```yaml
in_app_review: ^2.0.9
shared_preferences: ^2.x  # likely already present — verify
```

---

## Localization

Add to `lib/l10n/app_en.arb` and `lib/l10n/app_ru.arb`:
```
"rateUs": "Rate Us"          // EN
"rateUs": "Оцените нас"     // RU
```

---

## Error Handling

- If `InAppReview.isAvailable()` returns false (e.g., device has no Play Store), skip silently — no crash, no fallback UI needed.
- All `SharedPreferences` calls are wrapped in try/catch to avoid crashes on storage failure.

---

## Out of Scope

- Fallback to opening Play Market URL if in-app review is unavailable
- Tracking whether the user actually submitted a star rating (not exposed by Google's API)
- iOS support (app is Android-only)
