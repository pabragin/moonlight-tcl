# Application.mk for Moonlight

# Android 14 only (matches minSdk in app/build.gradle)
APP_PLATFORM := android-34

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Smaller native code: hide everything except the JNI entry points and let the linker drop
# unreferenced sections. Not a latency change, just less to load.
APP_CFLAGS += -fvisibility=hidden -ffunction-sections -fdata-sections
APP_LDFLAGS += -Wl,--gc-sections
