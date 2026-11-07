package app.gyrolet.mpvrx.ui.player

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvScriptAssetsTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun generatedScriptsAreNotPartOfTheUserInventory() {
    val root = temporary.newFolder()
    val scripts = File(root, "scripts").apply { mkdirs() }
    File(scripts, "user.lua").writeText("user")
    File(scripts, "user.js").writeText("user")
    File(scripts, "README.txt").writeText("notes")
    File(scripts, "helper").mkdirs()
    MpvScriptAssets.generatedDirectory(root).apply { mkdirs(); resolve("button.lua").writeText("generated") }
    assertEquals(setOf("user.lua", "user.js"), MpvScriptAssets.userScriptNames(root))
  }

  @Test fun onlyLuaHelpersAreMirroredWithoutRecopyingUnchangedFiles() {
    val scripts = temporary.newFolder("scripts")
    val modules = temporary.newFolder("modules")
    File(scripts, "entry.lua").writeText("entry")
    val helper = File(scripts, "helper/nested").apply { mkdirs() }
    File(helper, "module.lua").writeText("return {}")
    File(helper, "notes.txt").writeText("notes")
    assertEquals(1, MpvScriptAssets.syncLuaModules(scripts, modules))
    assertEquals("return {}", File(modules, "helper/nested/module.lua").readText())
    assertFalse(File(modules, "entry.lua").exists())
    assertFalse(File(modules, "helper/nested/notes.txt").exists())
    assertEquals(0, MpvScriptAssets.syncLuaModules(scripts, modules))
  }

  @Test fun staleModulesArePrunedAfterTheSourceInventorySucceeds() {
    val scripts = temporary.newFolder("scripts")
    val modules = temporary.newFolder("modules")
    File(scripts, "active").mkdirs()
    File(scripts, "active/module.lua").writeText("current")
    File(modules, "old").mkdirs()
    File(modules, "old/stale.lua").writeText("stale")
    MpvScriptAssets.syncLuaModules(scripts, modules, setOf("active"))
    assertTrue(File(modules, "active/module.lua").isFile)
    assertFalse(File(modules, "old").exists())
  }
}
