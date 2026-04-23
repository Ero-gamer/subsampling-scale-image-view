Subsampling Scale Image View Library (Enhanced Version based on Kotatsu SSIV Fork)
===========================

> A custom image view for Android, designed for photo galleries and displaying huge images (e.g. maps and building
> plans) without `OutOfMemoryError`s. Includes pinch to zoom, panning, rotation and animation support, and allows easy
> extension so you can add your own overlays and touch event detection.

# Differences from Kotatsu SSIV:

- Fixed and upgraded default image decoder to use ARGB_8888. (High Quality)
- Fixed visual bugs & optimizated performance.
- Updated dependencies & QOL improvements.

# Kotatsu SSIV Differences from [upstream](https://github.com/davemorrissey/subsampling-scale-image-view)

- Fully rewritten in Kotlin and using Coroutines for image loading (using from Java code is supported as well)
- Loading images from zip files (`ImageSource.zipEntry`)
- Support for ColorFilter
- Automatically stores and restores state (zoom and center)
- Supports Interpolator for animation
- Handle mouse and keyboard/dpad events for scaling and panning
- Supports downsampling
---

### Usage

1. Add it in your root build.gradle at the end of repositories:

   ```groovy
   allprojects {
	   repositories {
		   ...
		   maven { url 'https://jitpack.io' }
	   }
   }
   ```

2. Add the dependency

    ```groovy
    dependencies {
        implementation("com.github.Ero-gamer:subsampling-scale-image-view:$4.3.9")
    }
    ```

   See for versions at [JitPack](https://jitpack.io/#Ero-gamer/subsampling-scale-image-view)
