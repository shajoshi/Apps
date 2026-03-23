# Fix: Top Bar Overlapping System Status Bar

The app's content draws behind the system status bar because **Android 15 (API 35+) enforces edge-to-edge by default** when `targetSdk = 35+`, and the app has `targetSdk = 36` with no inset handling.

## Root Cause

Starting with Android 15, the system **ignores** `android:statusBarColor` and forces edge-to-edge — the app's content extends behind the status bar and navigation bar. The app currently:

1. **`targetSdk = 36`** in `build.gradle.kts` — triggers mandatory edge-to-edge on Android 15+ devices
2. **`Theme.OBD2App.NoActionBar`** on `MainActivity` — no ActionBar/Toolbar to automatically push content below the status bar
3. **No `fitsSystemWindows`** on any root layout or view
4. **No `WindowInsetsCompat` handling** anywhere in the code
5. The **default phone layout** (`layout/activity_main.xml` → `app_bar_main.xml` → `content_main.xml`) is a bare `FrameLayout` → `ViewPager2` chain with no inset awareness

The `layout-w600dp` variant has `fitsSystemWindows="true"` on its `DrawerLayout`, which is why this may only manifest on phone-sized screens.

## Fix

Apply `ViewCompat.setOnApplyWindowInsetsListener` in `MainActivity.onCreate()` to pad the root content view by the system bar insets. This is the modern, recommended approach.

### Changes

**`MainActivity.kt`** — add after `setContentView(binding.root)`:
```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// In onCreate, after setContentView:
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.updatePadding(
        top = systemBars.top,
        bottom = systemBars.bottom
    )
    insets
}
```

This single change ensures the `ViewPager2` and all fragments are pushed below the status bar and above the navigation bar on all screen sizes and Android versions.

### Why not `fitsSystemWindows="true"` in XML?
That attribute has inconsistent behavior across view types (ScrollView, DrawerLayout, ConstraintLayout all handle it differently). The programmatic `WindowInsetsCompat` approach is more reliable and is Google's recommended pattern for edge-to-edge apps.
