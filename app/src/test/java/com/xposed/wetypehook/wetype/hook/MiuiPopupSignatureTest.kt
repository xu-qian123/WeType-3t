package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuiPopupSignatureTest {
    @Test fun acceptsVerifiedOldAndNewOverloads() {
        assertTrue(matchesMiuiPopupShowSignature(List(5) { "int" }))
        assertTrue(matchesMiuiPopupShowSignature(List(5) { "int" } + "boolean"))
        assertTrue(matchesMiuiPopupShowSignature(List(5) { "int" } + List(2) { "boolean" }))
    }
    @Test fun rejectsOtherPopupActions() {
        assertFalse(matchesMiuiPopupShowSignature(emptyList()))
        assertFalse(matchesMiuiPopupShowSignature(listOf("android.view.View", "int", "int", "int", "int")))
        assertFalse(matchesMiuiPopupShowSignature(List(6) { "int" }))
        assertFalse(matchesMiuiPopupShowSignature(List(4) { "int" } + "boolean"))
        assertFalse(matchesMiuiPopupShowSignature(List(7) { "int" }))
        assertFalse(matchesMiuiPopupShowSignature(List(5) { "int" } + List(3) { "boolean" }))
        assertFalse(matchesMiuiPopupShowSignature(List(5) { "java.lang.Integer" }))
    }
}
