package com.smartguard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.smartguard.R
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.databinding.ItemFamilyMemberBinding
import com.smartguard.policy.UsageStore
import com.smartguard.policy.UserRole

/** Family list row: avatar, name, mode chip, today's screen-time bar. Used on Home and Family tabs. */
class FamilyAdapter(
    private val usage: UsageStore,
    private val onClick: (ProfileEntity) -> Unit
) : RecyclerView.Adapter<FamilyAdapter.VH>() {

    private var items: List<ProfileEntity> = emptyList()
    private var activeProfileId: Long? = null

    fun submit(list: List<ProfileEntity>, activeId: Long?) {
        items = list
        activeProfileId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFamilyMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val b = holder.binding
        val ctx = b.root.context
        val role = UserRole.fromString(p.role)

        b.avatar.text = p.name.trim().take(1).uppercase()
        b.name.text = p.name

        val (label, fg, bg) = when (role) {
            UserRole.CHILD -> Triple("Child", R.color.child_mode_fg, R.color.child_mode_bg)
            UserRole.TEEN -> Triple("Teen", R.color.teen_mode_fg, R.color.teen_mode_bg)
            else -> Triple("Parent", R.color.adult_mode_fg, R.color.adult_mode_bg)
        }
        b.roleChip.text = label
        b.roleChip.setTextColor(ContextCompat.getColor(ctx, fg))
        b.roleChip.background.mutate().setTint(ContextCompat.getColor(ctx, bg))

        val usingNow = if (p.id == activeProfileId) "Using now · " else ""
        if (role == UserRole.ADULT) {
            b.usageBar.visibility = View.GONE
            b.detail.text = "${usingNow}Full access"
        } else {
            val usedMin = (usage.usedSecondsToday(p.id) / 60).toInt()
            val budget = p.screenTimeBudgetMinutes.coerceAtLeast(1)
            val left = (budget - usedMin).coerceAtLeast(0)
            b.usageBar.visibility = View.VISIBLE
            b.usageBar.max = budget
            b.usageBar.setProgressCompat(usedMin.coerceAtMost(budget), false)
            val barColor = when {
                left == 0 -> R.color.accent_rose
                left <= 10 -> R.color.accent_amber
                else -> R.color.primary
            }
            b.usageBar.setIndicatorColor(ContextCompat.getColor(ctx, barColor))
            b.detail.text = if (left == 0) "${usingNow}Time's up for today" else "${usingNow}$left of $budget min left today"
        }
        b.root.setOnClickListener { onClick(p) }
    }

    class VH(val binding: ItemFamilyMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
