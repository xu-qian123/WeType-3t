package com.xposed.wetypehook.wetype.gesture

import org.junit.Assert.*
import org.junit.Test

class T9KeyNumberTest {
    class FirstKey {
        fun getMainText() = "@#"
        fun getId() = "S1_key_1"
    }
    class SymbolStripKey {
        fun getMainText() = "!"
        fun getId() = "S1_symbol_1"
    }
    class LetterKey {
        fun getMainText() = "A"
    }
    @Test fun usesSuppliedGetterForACompatibleReceiver() {
        val getter = LetterKey::class.java.getMethod("getMainText")
        assertEquals('a', KeyGestureResolver.resolveKeyChar(LetterKey(), getter))
    }
    @Test fun fallsBackWhenSuppliedGetterBelongsToAnotherClass() {
        val getter = LetterKey::class.java.getMethod("getMainText")
        assertEquals('1', KeyGestureResolver.resolveKeyChar(FirstKey(), getter, isT9 = true))
    }
    @Test fun fallsBackToIdOnlyForAnActualNumberedKey() {
        assertEquals('1', KeyGestureResolver.resolveKeyChar(FirstKey(), isT9 = true))
        assertEquals('\u0000', KeyGestureResolver.resolveKeyChar(SymbolStripKey(), isT9 = true))
    }
    @Test fun usesTheKeyNumberRatherThanKeyboardNumber() {
        for (key in '1'..'9') assertEquals(key, resolveT9KeyNumber("S1_key_$key"))
        assertEquals('1', resolveT9KeyNumber("t9_1"))
        assertEquals('7', resolveT9KeyNumber("S7_key_7"))
    }
    @Test fun rejectsSymbolStripAndFunctionIds() {
        for (id in listOf("S1_symbol_1", "S1_recycleview", "delete1", "!", "@#", "10"))
            assertNull(id, resolveT9KeyNumber(id))
    }
}
