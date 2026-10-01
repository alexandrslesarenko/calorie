[Русская версия](README.ru.md)

# Calorie

An Android calorie counter aimed at weight loss. It recognizes food on a photo or in a
text description with Claude Haiku 4.5, finds packaged products by barcode in Open Food
Facts, keeps a food diary and a weight log, and computes a daily calorie target for your
goal.

The app is in early testing; the name is a working one.

## Features

- Food by photo (camera or gallery), by description in words, by barcode or by hand.
  The recognized dishes can be corrected before saving: names, grams, meal.
- "My dishes": every saved dish is remembered, and repeating it needs no request to
  Claude. Answers are also cached, so sending the same photo or text again is free.
- If Claude is overloaded or there is no connection, the request is not lost: the app
  resends it on its own and shows the answer on Today, ready to check and add.
- Diary by meals, balance of each day against the target that day had (a later change of
  weight or activity does not rewrite the past), deficit or surplus over the last week in
  kilograms of fat.
- Weight log.
- Backup to a file and back: the diary, weight log, daily targets, "My dishes" and profile as JSON, which can
  be added to the data on another phone or replace it; the diary as CSV for spreadsheets.
- Daily target from the Mifflin-St Jeor equation, activity level and the chosen pace,
  with safety limits: not below 1200 / 1500 kcal and the basal metabolic rate, at most
  1% of body weight per week, maintenance when the goal is reached or BMI is below 18.5.
- Optional activity from [Pulsar](https://github.com/alexandrslesarenko/pulsar): heart
  rate on walks and workouts can lower the manual activity level, but not raise it - walks
  above it go to the deficit until the weight trend confirms the burn.
- Expenditure from the weight trend: after two weeks of a full diary and regular weigh-ins
  the app measures what you actually burn (what you ate plus what the weight lost) and uses
  it ahead of Pulsar and the manual level; the goal page shows it next to the formula.
- Interface in 9 languages: English, Russian, German, French, Spanish, Italian,
  Japanese, Korean, Chinese (Simplified). Claude answers in the app language.

## Claude API key

Recognition by photo and description needs your own Claude API key from
[platform.claude.com](https://platform.claude.com). It is paid per use, separately from
Claude subscriptions: about 0.4 cents per photo with Claude Haiku 4.5. The app counts the
spending itself from the token usage in the answers. Barcodes, the diary, "My dishes"
and cached answers work without a key.

The key is encrypted with a key from the Android Keystore and excluded from cloud
backup.

## Privacy

- Photos and descriptions are sent to the Claude API. Photos are re-encoded before
  sending, so EXIF data and geotags do not leave the phone.
- Barcodes are sent to Open Food Facts (no account or key).
- The diary, weight log and profile stay on the phone.

## Requirements

- Android 12 (API 31) or newer.
- Google Play services for the barcode scanner.
- A Claude API key for photo and text recognition.

## Building

JDK 17 and the Android SDK are required. Put the SDK path into `local.properties`
(`sdk.dir=...`) or set `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug          # install on a connected phone
./gradlew :app:testDebugUnitTest     # unit tests, no device and no API calls
```

## Pulsar link

Calorie reads per-minute heart rate on walks and workouts from Pulsar's content provider.
It is guarded by a signature permission, so both apps must be signed with the same key.
Debug builds made on one machine use the same debug keystore, so this works out of the
box. If Calorie was installed before Pulsar, reinstall Calorie to get the permission.

## Disclaimer

Calorie is not a medical device and does not give medical advice. Calorie and nutrient
values from photos and descriptions are estimates and can be wrong. Talk to a doctor
before changing your diet, especially with health conditions.

## License

Copyright 2026 Alexandr Slessarenko

Licensed under the [Apache License, Version 2.0](LICENSE).
