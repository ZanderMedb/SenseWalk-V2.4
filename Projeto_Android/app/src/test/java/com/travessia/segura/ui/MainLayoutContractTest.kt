package com.travessia.segura.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class MainLayoutContractTest {
    @Test fun crossingAndStopControlsExistInEveryMainLayout() {
        val variants = File("src/main/res").walkTopDown().filter { it.name == "activity_main.xml" }.toList()
        assertTrue("Layout resources must be available", variants.isNotEmpty())
        for (file in variants) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("Button")
            val ids = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:id")?.nodeValue }.toSet()
            for (id in listOf("btnCrossing", "btnToggle", "btnVoice", "btnConfig", "btnSource"))
                assertTrue("${file.path}: missing $id", "@+id/$id" in ids)
        }
    }
}
