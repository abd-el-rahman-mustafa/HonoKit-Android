package com.aamus.powerpdf.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dynamic XFA form saved with its pages drawn is still dynamic when its configuration requires it (desktop xfaconfig.test.ts). */
class XfaConfigTest {
    private fun config(value: String) =
        "<config xmlns=\"http://www.xfa.org/schema/xci/3.1/\"><acrobat><acrobat7><dynamicRender>$value</dynamicRender></acrobat7></acrobat></config>"

    @Test
    fun requiredIsDynamic() {
        assertTrue(requiresDynamicRender(config("required")))
        assertTrue(requiresDynamicRender("<x:config xmlns:x=\"x\"><x:acrobat><x:acrobat7><x:dynamicRender> required </x:dynamicRender></x:acrobat7></x:acrobat></x:config>"))
    }

    @Test
    fun aWholeXdpIsReadToo() {
        assertTrue(requiresDynamicRender("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\">${config("required")}<template><subform/></template></xdp:xdp>"))
    }

    @Test
    fun forbiddenMissingOrElsewhereIsNot() {
        assertFalse(requiresDynamicRender(config("forbidden")))
        assertFalse(requiresDynamicRender("<template><subform/></template>"))
        assertFalse(requiresDynamicRender("<template><acrobat><acrobat7><dynamicRender>required</dynamicRender></acrobat7></acrobat></template>"))
        assertFalse(requiresDynamicRender("<config><present><dynamicRender>required</dynamicRender></present></config>"))
        // A configuration with no end tag counts as not required.
        assertFalse(requiresDynamicRender("<config><acrobat><acrobat7><dynamicRender>required</dynamicRender>"))
    }
}
