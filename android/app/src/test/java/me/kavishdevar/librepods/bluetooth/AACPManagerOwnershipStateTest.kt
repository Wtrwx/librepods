package me.kavishdevar.librepods.bluetooth

import java.lang.reflect.Proxy
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.Opcodes
import org.junit.Assert.*
import org.junit.Test

class AACPManagerOwnershipStateTest {
    private fun ownership(manager: AACPManager, value: Int) = manager.createDataPacket(
        manager.createControlCommandPacket(OWNS_CONNECTION.value, byteArrayOf(value.toByte()))
    )

    @Test fun confirmedOwnershipReplacesTheOldValueAndNotifiesOnce() {
        val manager = AACPManager()
        val observed = mutableListOf<Int>()
        manager.registerControlCommandListener(OWNS_CONNECTION, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(command: AACPManager.ControlCommand) {
                observed += command.value[0].toInt()
            }
        })
        for (value in listOf(0, 1, 0, 1)) {
            manager.receivePacket(ownership(manager, value))
            assertEquals(value == 1, manager.owns)
            assertEquals(value, manager.getControlCommandStatus(OWNS_CONNECTION)!!.value[0].toInt())
            assertEquals(1, manager.controlCommandStatusList.count { it.identifier == OWNS_CONNECTION })
            manager.sendControlCommand(OWNS_CONNECTION.value, 1 - value)
            assertEquals(value == 1, manager.owns)
            assertEquals(value, manager.getControlCommandStatus(OWNS_CONNECTION)!!.value[0].toInt())
        }
        assertEquals(listOf(0, 1, 0, 1), observed)
        manager.disconnected()
        assertFalse(manager.owns)
        assertNull(manager.getControlCommandStatus(OWNS_CONNECTION))
    }

    @Test fun malformedSourceDoesNotReplayThePreviousSourceCallback() {
        val manager = AACPManager()
        var sourceCallbacks = 0
        manager.setPacketCallback(Proxy.newProxyInstance(
            AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)
        ) { _, method, _ ->
            if (method.name == "onAudioSourceReceived") sourceCallbacks++
            null
        } as AACPManager.PacketCallback)
        val source = byteArrayOf(4, 0, 4, 0, Opcodes.AUDIO_SOURCE, 0, 6, 5, 4, 3, 2, 1, 2)
        manager.receivePacket(source)
        assertEquals(1, sourceCallbacks)
        for (length in 6..12) manager.receivePacket(source.copyOf(length))
        manager.receivePacket(source.copyOf().also { it[12] = 99 })
        assertEquals(1, sourceCallbacks)
        assertEquals("01:02:03:04:05:06", manager.audioSource!!.mac)
        manager.receivePacket(source)
        assertEquals(2, sourceCallbacks)
        // The generic AACP header can be valid while the Smart Routing sender is incomplete.
        for (length in 6..11) {
            manager.receivePacket(ByteArray(length).also {
                it[0] = 4; it[2] = 4; it[4] = Opcodes.SMART_ROUTING_RESP
            })
        }
    }
}
