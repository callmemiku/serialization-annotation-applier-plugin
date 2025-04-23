# ktor serialization extension plugin
Plugin helps use generated classes with Ktor

## Features
Since ktor uses kotlinx.serialization every data class has to have @Serializable annotation, that's why:
- Plugin adds @Serializable to every class on path provided into input field of configuration
- Plugin applies custom serializers if they exist. It looks for serializers on package provided with serializers field

## Installation
In plugins section of your `build.gradle(.kts)` add following:
```kotlin
    id("io.github.callmemiku.ktor-extension") version "1.0.0"
```

## Usage
1. Configure plugin in your `build.gradle(.kts)` file:

```kotlin
serializationInjectorConfiguration {
    input = "path to generated files"
    serializers = "parent package of serializers"
}
```

2. Build your project, plugin will automatically enrich files on path if they exist

