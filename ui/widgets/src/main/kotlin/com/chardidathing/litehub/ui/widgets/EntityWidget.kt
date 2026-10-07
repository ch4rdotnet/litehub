package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

// the config every entity backed widget takes
@Serializable
data class EntityConfig(val entity: String, val name: String? = null, val icon: String? = null)

// a widget fed one entity. it only gets snapshots and a tap callback, never the repository
abstract class EntityWidget(
    context: Context,
    theme: ResolvedTheme,
    protected val icons: Icons,
    val config: EntityConfig,
) : WidgetView(context, theme) {

    var onTap: (() -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
        }

    // a hold, for entities with more to them than a toggle (a light's controls)
    var onHold: (() -> Unit)? = null

    override fun hold(x: Float, y: Float): Boolean {
        val h = onHold ?: return false
        h()
        return true
    }

    protected var snapshot: EntitySnapshot = EntitySnapshot.Connecting
        private set

    init {
        setOnClickListener { onTap?.invoke() }
    }

    fun show(snapshot: EntitySnapshot) {
        if (snapshot == this.snapshot) return
        this.snapshot = snapshot
        setBadge((snapshot as? EntitySnapshot.Stale)?.reason)
        onContentChanged()
        invalidate()
    }

    // a failed action, shown in place of the state
    private val error: String?
        get() = when (val s = snapshot) {
            is EntitySnapshot.Live -> s.error
            is EntitySnapshot.Stale -> s.error
            else -> null
        }

    protected val entity: Entity?
        get() = when (val s = snapshot) {
            is EntitySnapshot.Live -> s.entity
            is EntitySnapshot.Stale -> s.entity
            else -> null
        }

    protected val name: String
        get() = config.name ?: entity?.attribute("friendly_name") ?: config.entity

    protected val iconName: String
        get() = EntityIcons.name(config.entity, entity, config.icon, icons)

    // what to say when there's no entity to draw, null when there is one
    protected val problem: String?
        get() = error ?: when (val s = snapshot) {
            EntitySnapshot.Connecting -> "connecting"
            EntitySnapshot.NotFound -> "not found in home assistant"
            is EntitySnapshot.Failed -> s.reason
            else -> null
        }

    protected val problemIsError: Boolean
        get() = error != null || snapshot is EntitySnapshot.NotFound || snapshot is EntitySnapshot.Failed
}
