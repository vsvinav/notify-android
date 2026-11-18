package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test

class PhoneAppearanceTest {
    @Test fun appearanceDefaultsToSystemAndOnlyChangesAfterSuccessfulLocalSave() {
        var stored: String? = null
        var writable = true
        val settings = AppearancePreference({ stored }, { if (writable) { stored = it; true } else false })
        assertEquals(PhoneAppearance.System, settings.current)
        assertTrue(settings.current.isDark(true))
        assertFalse(settings.current.isDark(false))
        settings.set(PhoneAppearance.Dark)
        assertEquals(PhoneAppearance.Dark, AppearancePreference({ stored }, { true }).current)
        assertTrue(settings.current.isDark(false))
        writable = false
        assertThrows(IllegalStateException::class.java) { settings.set(PhoneAppearance.Light) }
        assertEquals(PhoneAppearance.Dark, settings.current)
        writable = true
        settings.set(PhoneAppearance.Light)
        assertFalse(settings.current.isDark(true))
        settings.set(PhoneAppearance.System)
        assertEquals("system", stored)
    }
}
