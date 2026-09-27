package com.smartguard.ui.handover

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smartguard.databinding.ItemHomeAppBinding

/** An app as shown on the hand-over home screens. */
data class HomeApp(val pkg: String, val label: String, val icon: Drawable?, val launch: Intent)

/** Launchable apps for [packages] (or every launchable app when null), sorted like a launcher. */
fun loadHomeApps(context: Context, packages: Set<String>? = null, exclude: Set<String> = emptySet()): List<HomeApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName && it !in exclude && (packages == null || it in packages) }
        .mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return@mapNotNull null }
            HomeApp(pkg, pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(info) }.getOrNull(), launch)
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * Home-screen style app grid. In selection mode a tap toggles the app (ring + check badge);
 * otherwise a tap opens it.
 */
class HomeAppsAdapter(
    private val selectable: Boolean,
    private val onSelectionChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<HomeAppsAdapter.VH>() {

    private var items: List<HomeApp> = emptyList()
    private val selected = linkedSetOf<String>()

    val selection: Set<String> get() = selected

    fun submit(list: List<HomeApp>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHomeAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val b = holder.binding
        b.appIcon.setImageDrawable(app.icon)
        b.appLabel.text = app.label
        showSelected(holder, app.pkg in selected, animate = false)
        b.root.setOnClickListener {
            if (!selectable) {
                it.context.startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@setOnClickListener
            }
            val on = if (app.pkg in selected) { selected.remove(app.pkg); false } else { selected.add(app.pkg); true }
            showSelected(holder, on, animate = true)
            onSelectionChanged(selected)
        }
    }

    private fun showSelected(holder: VH, on: Boolean, animate: Boolean) {
        val b = holder.binding
        b.selectRing.visibility = if (on) View.VISIBLE else View.INVISIBLE
        b.checkBadge.visibility = if (on) View.VISIBLE else View.INVISIBLE
        b.root.contentDescription = "${b.appLabel.text}${if (on) ", selected" else ""}"
        val scale = if (on) 1.08f else 1f
        if (animate) {
            b.appIcon.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            if (on) {
                b.checkBadge.scaleX = 0.4f
                b.checkBadge.scaleY = 0.4f
                b.checkBadge.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
        } else {
            b.appIcon.scaleX = scale
            b.appIcon.scaleY = scale
        }
    }

    class VH(val binding: ItemHomeAppBinding) : RecyclerView.ViewHolder(binding.root)
}
