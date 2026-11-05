# Third-party components

This application incorporates the following open-source components. Their respective copyrights and licenses remain with their authors.

- **VideoLAN LibVLC for Android 3.7.7** — VideoLAN / VideoLabs and contributors. LGPL 2.1 or later. [Android bindings and build scripts](https://code.videolan.org/videolan/libvlcjni), [LibVLC](https://code.videolan.org/videolan/vlc), [Android SDK](https://code.videolan.org/videolan/libvlc-android-samples). The SDK includes native media modules and their third-party notices; retain applicable component licenses when redistributing or modifying the SDK.
- **jCIFS-ng 2.1.10** — AgNO3 GmbH & Co. KG, Michael B. Allen and contributors. LGPL 2.1 or later. [Source and license](https://github.com/AgNO3/jcifs-ng/tree/jcifs-ng-2.1.10).
- **Bouncy Castle bcprov-jdk18on 1.76** (jCIFS-ng transitive dependency) — The Legion of the Bouncy Castle Inc. MIT-style license. [License](https://www.bouncycastle.org/licence.html).
- **SLF4J 1.7.36** — QOS.ch and contributors. MIT license. [License](https://www.slf4j.org/license.html).

- **AndroidX** (LibVLC transitive dependencies) — The Android Open Source Project. Apache 2.0. [Source](https://android.googlesource.com/platform/frameworks/support/), [license text](licenses/Apache-2.0.txt).
- **Kotlin standard library 2.2.10** (LibVLC transitive dependency) — JetBrains and contributors. Apache 2.0. [Source](https://github.com/JetBrains/kotlin/tree/v2.2.10), [license](licenses/Kotlin.txt).

The JVM and device test dependencies (JUnit and AndroidX Test) are used only for verification and are not part of the main application APK.

[GNU LGPL 2.1 license text](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).

## License texts and rebuilding

NetTV's own source is MIT licensed; that does not replace the licenses of the libraries above. Full LGPL 2.1, GPL 2.0 (referenced by LGPL), Apache 2.0, and permissive component license texts are provided in `licenses/`. Release assets include `NetTV-1.3.5-licenses.zip` with these notices.

The published APKs use unmodified Maven artifacts `org.videolan.android:libvlc-all:3.7.7`, `eu.agno3.jcifs:jcifs-ng:2.1.10`, `org.slf4j:slf4j-nop:1.7.36`, and their runtime dependencies. To inspect the resolved versions run:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

The complete NetTV source and Gradle build files are public. Library source and build instructions are available from the upstream projects linked above; jCIFS' matching release is tagged `jcifs-ng-2.1.10`. To use modified libraries, replace the dependency with a locally built AAR/JAR in `app/build.gradle`, then rebuild with `./gradlew assembleDebug`. APKs dynamically load their packaged native LibVLC shared libraries. You may modify and rebuild the LGPL components and debug the resulting library integration. Your rebuilt APK uses your own signing key; an existing installation with a different signature must first be removed (which clears its local data).
