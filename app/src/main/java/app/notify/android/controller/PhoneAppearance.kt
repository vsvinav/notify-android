package app.notify.android.controller

enum class PhoneAppearance(val stored: String) {
    System("system"), Light("light"), Dark("dark");

    fun isDark(systemDark: Boolean) = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

class AppearancePreference(read: () -> String?, private val write: (String) -> Boolean) {
    var current = PhoneAppearance.entries.firstOrNull { it.stored == read() } ?: PhoneAppearance.System
        private set

    fun set(value: PhoneAppearance) {
        check(write(value.stored)) { "Appearance could not be saved on this phone. Check device storage." }
        current = value
    }
}
