package com.witvpn.ikev2.presentation.ui.servers

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ItemServerRowBinding

/** Строка списка серверов. [payload] — Server (GW/AWG) или VlessConfig. */
sealed class ServerRow {
    abstract val key: String

    data class Auto(
        val selected: Boolean,
        val subtitle: String,
    ) : ServerRow() {
        override val key: String get() = KEY_AUTO
    }

    data class Node(
        override val key: String,
        val title: String,
        val subtitle: String?,
        @DrawableRes val iconRes: Int,
        val hysteriaLogo: Boolean,
        val load: Int?,
        val selected: Boolean,
        val locked: Boolean,
        val payload: Any,
    ) : ServerRow()

    companion object {
        const val KEY_AUTO = "__auto__"
    }
}

enum class RowMark { NONE, SELECTED, CURRENT, LOCKED }

class ServerListAdapter(
    private val onClick: (ServerRow) -> Unit,
) : ListAdapter<ServerRow, ServerListAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = Holder(binding)
        binding.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < itemCount) onClick(getItem(pos))
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val mark = when (row) {
            is ServerRow.Auto -> if (row.selected) RowMark.SELECTED else RowMark.NONE
            is ServerRow.Node -> when {
                row.selected -> RowMark.SELECTED
                row.locked -> RowMark.LOCKED
                else -> RowMark.NONE
            }
        }
        ServerRowBinder.bind(holder.binding, row, mark)
    }

    class Holder(val binding: ItemServerRowBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<ServerRow>() {
        override fun areItemsTheSame(oldItem: ServerRow, newItem: ServerRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: ServerRow, newItem: ServerRow) = oldItem == newItem
    }
}

/** Общая отрисовка строки — и для списка, и для карточки «Текущий сервер». */
object ServerRowBinder {

    fun bind(b: ItemServerRowBinding, row: ServerRow, mark: RowMark) {
        val ctx = b.root.context
        when (row) {
            is ServerRow.Auto -> {
                setAutoIcon(b)
                b.tvTitle.text = ctx.getString(R.string.auto_select)
                setTitleLogo(b, show = false)
                setSubtitle(b, row.subtitle)
                setLoad(b, null)
            }
            is ServerRow.Node -> {
                b.imgIcon.background = null
                b.imgIcon.setPadding(0, 0, 0, 0)
                b.imgIcon.setImageResource(row.iconRes)
                b.tvTitle.text = row.title
                setTitleLogo(b, row.hysteriaLogo)
                setSubtitle(b, row.subtitle)
                setLoad(b, row.load)
            }
        }
        setMark(b, mark)
        b.rowRoot.isSelected = mark == RowMark.SELECTED
    }

    fun setAutoIcon(b: ItemServerRowBinding) {
        val pad = (9 * b.root.resources.displayMetrics.density).toInt()
        b.imgIcon.setBackgroundResource(R.drawable.bg_srv_auto_icon)
        b.imgIcon.setPadding(pad, pad, pad, pad)
        b.imgIcon.setImageResource(R.drawable.ic_srv_bolt)
    }

    private fun setSubtitle(b: ItemServerRowBinding, text: String?) {
        b.tvSubtitle.text = text
        b.tvSubtitle.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setMark(b: ItemServerRowBinding, mark: RowMark) {
        val ctx = b.root.context
        when (mark) {
            RowMark.NONE -> {
                b.imgStatus.visibility = View.GONE
                b.imgStatus.setImageDrawable(null)
                b.imgStatus.background = null
            }
            RowMark.LOCKED -> {
                b.imgStatus.visibility = View.VISIBLE
                b.imgStatus.background = null
                b.imgStatus.setPadding(0, 0, 0, 0)
                b.imgStatus.setImageResource(R.drawable.ic_srv_lock)
            }
            RowMark.SELECTED, RowMark.CURRENT -> {
                val pad = (5 * b.root.resources.displayMetrics.density).toInt()
                @ColorRes val color = if (mark == RowMark.CURRENT) R.color.blue else R.color.srv_selected
                b.imgStatus.visibility = View.VISIBLE
                b.imgStatus.setBackgroundResource(R.drawable.shape_circle)
                b.imgStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
                b.imgStatus.setPadding(pad, pad, pad, pad)
                b.imgStatus.setImageResource(R.drawable.ic_srv_check)
            }
        }
    }

    fun setLoad(b: ItemServerRowBinding, percent: Int?) {
        if (percent == null) {
            b.loadContainer.visibility = View.GONE
            return
        }
        val value = percent.coerceIn(0, 100)
        val color = ContextCompat.getColor(b.root.context, loadColor(value))
        b.loadContainer.visibility = View.VISIBLE
        b.tvLoad.text = "$value%"
        b.tvLoad.setTextColor(color)
        b.loadBar.progressTintList = ColorStateList.valueOf(color)
        // Ненулевая нагрузка всегда видна на полоске (текст остаётся точным)
        b.loadBar.progress = if (value > 0) value.coerceAtLeast(6) else 0
    }

    @ColorRes
    fun loadColor(percent: Int): Int = when {
        percent >= 80 -> R.color.color_load_critical
        percent >= 60 -> R.color.color_load_high
        percent >= 30 -> R.color.color_load_medium
        else -> R.color.color_load_low
    }

    /** Лого Hysteria2 слева от названия; всегда сбрасываем при переиспользовании строки. */
    private fun setTitleLogo(b: ItemServerRowBinding, show: Boolean) {
        val tv = b.tvTitle
        if (!show) {
            tv.setCompoundDrawablesRelative(null, null, null, null)
            tv.compoundDrawablePadding = 0
            return
        }
        val d = ContextCompat.getDrawable(tv.context, R.drawable.ic_hysteria_logo)?.mutate()
        if (d == null) {
            tv.setCompoundDrawablesRelative(null, null, null, null)
            tv.compoundDrawablePadding = 0
            return
        }
        val w = (tv.textSize * 1.12f).toInt().coerceAtLeast(1)
        val h = (w * 16f / 22f).toInt().coerceAtLeast(1)
        d.setBounds(0, 0, w, h)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.compoundDrawablePadding = (4f * tv.resources.displayMetrics.density).toInt()
    }
}
