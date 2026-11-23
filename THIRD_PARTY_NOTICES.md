# Third-Party Notices

DIM8 BT Thermometer is licensed under [`GPL-3.0-only`](LICENSE). The components below remain under their own licenses. Everything included in the Android app is under a GPLv3-compatible license. This inventory is engineering documentation and is not legal advice.

## Included in the Android app

| Component | Version | License | Notice |
|---|---|---|---|
| [Kotlin Standard Library](https://github.com/JetBrains/kotlin) | 2.2.10 | Apache-2.0 | [License](LICENSES/Apache-2.0.txt), [NOTICE](LICENSES/NOTICE-Kotlin.txt) |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) | 13.0 | Apache-2.0 | [License](LICENSES/Apache-2.0.txt) |
| [Material Icons](https://github.com/google/material-design-icons) (settings and back arrow) | Not recorded | Apache-2.0 | [License](LICENSES/Apache-2.0.txt) |

The two icons are `app/src/main/res/drawable/ic_settings.xml` and `ic_back.xml`. Their vector paths follow the Google Material Icons designs. Copyright Google LLC.

The Kotlin project identifies third-party code within its standard library, including:

| Origin | License | Notice |
|---|---|---|
| Google Web Toolkit, © 2007-08 Google Inc. | Apache-2.0 | [License](LICENSES/Apache-2.0.txt) |
| Guava, © 2011 The Guava Authors | Apache-2.0 | [License](LICENSES/Apache-2.0.txt) |
| ThreeTen Backport, © 2007-present Stephen Colebourne & Michael Nascimento Santos | BSD-3-Clause | [License](LICENSES/BSD-3-Clause-ThreeTenBP.txt) |
| Boost special math functions, © Eric Ford & Hubert Holin 2001 | BSL-1.0 | [License](LICENSES/BSL-1.0-Boost.txt) |

The complete list is in Kotlin's [license documentation](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/README.md).

## Build and test tools

These are used to build or test the project and are not included in the app, so their licenses do not affect the license of the distributed APK.

| Component | License | Use |
|---|---|---|
| [Gradle Wrapper](https://github.com/gradle/gradle) | Apache-2.0 | `gradlew`, `gradlew.bat`, and `gradle/wrapper/` are included in this repository with their original notices |
| Android Gradle Plugin and Android SDK | Google's terms | Installed separately by the developer |
| [JUnit 4.13.2](https://github.com/junit-team/junit4) | EPL-1.0 | Unit tests only, downloaded at build time |
| [Pillow](https://github.com/python-pillow/Pillow) | MIT-CMU | Optional, used by `tools/generate_brand_assets.py` |

## H5182 protocol research

The H5182 advertisement format was learned from the public projects below. **No library or source file from these projects is included in the app.** `H5182Decoder.kt` is an independent Kotlin implementation.

| Project | License | How it was used |
|---|---|---|
| [GoveeBTTempLogger](https://github.com/wcbonner/GoveeBTTempLogger), © 2020 William C. Bonner | MIT | Primary protocol and capture reference. Two sample advertisements are used as test data |
| [govee-ble](https://github.com/Bluetooth-Devices/govee-ble) | Apache-2.0, with its parser marked as MIT and derived from [bleparser](https://github.com/Ernst79/bleparser) | Protocol reference. One sample advertisement is used as test data |
| [GoveeBT-Python](https://github.com/TechnoSwiss/GoveeBT-Python), © 2025 TechnoSwiss | MIT | Corroborating reference, itself based on GoveeBTTempLogger |

The three sample advertisements appear in `app/src/test/java/com/dim8labs/btthermometer/core/H5182DecoderTest.kt`. They are test inputs only and are not part of the app. As a precaution, the [GoveeBTTempLogger MIT license](LICENSES/MIT-GoveeBTTempLogger.txt) and [Apache License](LICENSES/Apache-2.0.txt) are retained here.

The fixture `app/src/test/resources/fixtures/h5182-pixel-xl-2026-11-22.txt` is an original capture from this project's hardware, with Bluetooth address bytes removed.

## Trademarks

Govee, Android, Google, Pixel, Bluetooth, Kotlin, and other names are the property of their respective owners and are used only for identification. No affiliation or endorsement is implied.
