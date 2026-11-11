package com.travessia.segura.road

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import android.hardware.usb.UsbManager

@RunWith(AndroidJUnit4::class)
class UsbRuntimeTest {
    @Test fun nativeLibraryAndUsbEnumeration() {
        // Carregar todas as dependências nativas reais, não apenas verificar presença no ZIP.
        Class.forName("com.serenegiant.usb.UVCCamera")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(UsbManager::class.java)
        val cameras = manager.deviceList.values.filter { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 14 } }
        println("USB_UVC_COUNT=${cameras.size}")
        for (camera in cameras) println("USB_UVC device=${camera.vendorId}:${camera.productId} permission=${manager.hasPermission(camera)}")
        assertNotNull(manager)
    }
}
