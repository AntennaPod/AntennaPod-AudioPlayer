# AntennaPod-AudioPlayer

This is the repository for library code separated from the main repository for licensing compliance.

## License

All code in this repository is licensed under the Apache License, Version 2.0. 
You can find the license text in the LICENSE file.

## Local testing

**settings.gradle**
```
...
include ':aap'
project(':aap').projectDir = new File('../AntennaPod-AudioPlayer/library')
```

**app/build.gradle** and **core/build.gradle**


Edit both ``AntennaPod/app/build.gradle`` and ``AntennaPod/core/build.gradle``
```

dependencies {
    ....
    compile project(":aap")
}
