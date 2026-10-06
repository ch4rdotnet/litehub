package com.chardidathing.litehub

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.model.Config
import com.chardidathing.litehub.ui.components.MenuView
import com.chardidathing.litehub.ui.editor.EntityPicker
import com.chardidathing.litehub.ui.editor.LayoutEditorView
import com.chardidathing.litehub.ui.editor.SettingsSheet
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.Legend
import com.chardidathing.litehub.ui.widgets.WidgetSchemas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

// the on-device editor, a stack of screens over the dashboard: the layout, a widget's settings,
// the entity picker. done writes config.json
class EditorFlow(
    private val activity: Activity,
    private val app: LitehubApp,
    private val container: FrameLayout,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {

    private val stack = ArrayList<View>()
    private var editor: LayoutEditorView? = null
    val isOpen get() = stack.isNotEmpty()

    fun open(theme: ResolvedTheme, config: Config, page: Int, legend: Legend) {
        close()
        currentTheme = theme
        val view = LayoutEditorView(activity, theme, config, page, object : LayoutEditorView.Host {
            override fun openSettings(page: Int, index: Int) = settings(theme, legend, page, index)
            override fun addWidget(page: Int) = chooseType(theme, page)
            override fun done(config: Config) = save(config)
            override fun cancel() = close()
        })
        editor = view
        push(view)
    }

    fun close() {
        hideKeyboard()
        stack.forEach(container::removeView)
        stack.clear()
        editor = null
    }

    // back pops one screen, from the layout itself it leaves without saving
    fun back() {
        if (stack.size > 1) pop() else close()
    }

    private fun chooseType(theme: ResolvedTheme, page: Int) {
        val schemas = WidgetSchemas.all
        push(MenuView(activity, theme, "add widget", null, schemas.map { it.name } + "cancel") { i ->
            pop()
            if (i < schemas.size && editor?.addWidget(page, schemas[i].type) == false) {
                push(MenuView(activity, theme, "no room on this page", "move or shrink something first, or add a page", listOf("ok")) { pop() })
            }
        })
    }

    private fun settings(theme: ResolvedTheme, legend: Legend, page: Int, index: Int) {
        val e = editor ?: return
        val placement = e.widget(page, index) ?: return
        val schema = WidgetSchemas.of(placement.type) ?: return
        push(SettingsSheet(activity, theme, schema, placement.config, legend, object : SettingsSheet.Host {
            override fun pickEntity(domains: List<String>, onPicked: (String) -> Unit) = pick(theme, domains, onPicked)
            override fun save(settings: JsonObject) {
                hideKeyboard()
                pop()
                e.updateWidget(page, index, settings)
            }
            override fun remove() {
                hideKeyboard()
                pop()
                e.removeWidget(page, index)
            }
            override fun cancel() {
                hideKeyboard()
                pop()
            }
        }))
    }

    private fun pick(theme: ResolvedTheme, domains: List<String>, onPicked: (String) -> Unit) {
        hideKeyboard()
        val picker = EntityPicker(activity, theme, domains, onPicked = { id ->
            hideKeyboard()
            pop()
            onPicked(id)
        }, onCancel = {
            hideKeyboard()
            pop()
        })
        push(picker)
        scope.launch { picker.show(app.ha.catalogue()) }
    }

    private fun save(config: Config) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { write(config) }
            result.onSuccess {
                close()
                onSaved()
            }.onFailure {
                push(failure(it.message ?: "couldn't save"))
            }
        }
    }

    private fun failure(message: String): View {
        val theme = currentTheme ?: return View(activity)
        return MenuView(activity, theme, "couldn't save", message, listOf("ok")) { pop() }
    }

    private var currentTheme: ResolvedTheme? = null

    // checked by decoding before it's written, a layout that wouldn't load never reaches disk
    private fun write(config: Config): Result<Unit> = try {
        val text = ConfigCodec.encode(config)
        ConfigCodec.decode(text)
        val file = File(app.filesDir, LitehubApp.CONFIG_FILE)
        file.writeAtomic(text)
        Result.success(Unit)
    } catch (e: ConfigException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(IOException("couldn't write config.json", e))
    }

    private fun push(view: View) {
        stack += view
        container.addView(view)
    }

    private fun pop() {
        val top = stack.removeLastOrNull() ?: return
        container.removeView(top)
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(container.windowToken, 0)
    }
}
