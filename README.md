# AntennaPod-AudioPlayer

This is the repository for library code separated from the main repository for licensing compliance.

## License

All code in this repository is licensed under the Apache License, Version 2.0. 
You can find the license text in the LICENSE file.

## Local testing

Go to your workspace
```
mkdir AntennaPod/app/libs/ AntennaPod/core/libs/
AntennaPod-AudioPlayer/gradlew clean
cp AntennaPod-AudioPlayer/library/build/outputs/aar/library-debug.aar AntennaPod/app/libs/library.aar
cp AntennaPod-AudioPlayer/library/build/outputs/aar/library-debug.aar AntennaPod/core/libs/library.aar
```

Edit both ``AntennaPod/app/build.gradle`` and ``AntennaPod/core/build.gradle``
```
repositories {
    ...
    flatDir {
        dirs 'libs'
    }
}

dependencies {
    ....
    compile 'org.antennapod.audio:library:1.0@aar'
}
