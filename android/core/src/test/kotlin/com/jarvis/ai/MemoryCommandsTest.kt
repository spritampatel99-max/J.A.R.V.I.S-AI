package com.jarvis.ai

import com.jarvis.ai.memory.MemoryCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryCommandsTest {

    @Test fun `remember that saves what follows`() {
        assertEquals("My sister is called Asha", MemoryCommands.remember("Remember that my sister is called Asha"))
        assertEquals("I prefer short answers", MemoryCommands.remember("please remember that I prefer short answers."))
        assertEquals("The wifi code is on the fridge", MemoryCommands.remember("keep in mind that the wifi code is on the fridge"))
    }

    @Test fun `save to memory phrases work in both orders`() {
        assertEquals("My locker number is 42", MemoryCommands.remember("save to memory: my locker number is 42"))
        assertEquals("I am allergic to nothing", MemoryCommands.remember("add this to my memory - I am allergic to nothing"))
        assertEquals("My bus leaves at 8", MemoryCommands.remember("save my bus leaves at 8 to memory"))
    }

    @Test fun `remember to is a reminder request, not a memory`() {
        assertNull(MemoryCommands.remember("remember to call mom tomorrow"))
        assertNull(MemoryCommands.remember("remind me to call mom"))
    }

    @Test fun `questions and chatter never save`() {
        assertNull(MemoryCommands.remember("do you remember that I like tea?"))
        assertNull(MemoryCommands.remember("what do you remember"))
        assertNull(MemoryCommands.remember("I remember that day"))
        assertNull(MemoryCommands.remember("remember that"))
        assertNull(MemoryCommands.remember("remember that x"))
        assertNull(MemoryCommands.remember(""))
    }

    @Test fun `forgetting is not done by chat`() {
        assertNull(MemoryCommands.remember("forget that my sister is called Asha"))
        assertNull(MemoryCommands.remember("delete my memory"))
    }
}
