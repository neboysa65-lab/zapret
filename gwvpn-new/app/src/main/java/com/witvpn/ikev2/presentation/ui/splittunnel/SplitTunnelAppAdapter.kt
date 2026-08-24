package com.witvpn.ikev2.presentation.ui.splittunnel

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ItemSplitTunnelAppBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List of installed applications with a per-app selection switch. What a tick
 * means depends on the Split Tunneling mode ("Only selected" — the app uses
 * the tunnel, "All except selected" — the app bypasses it); the screen header
 * above the list spells it out. Icons are loaded off the main thread and
 * cached, so scrolling a long list stays smooth and the initial load never
 * blocks the UI.
 */
class SplitTunnelAppAdapter(
    private val scope: CoroutineScope,
    private val onToggle: (packageName: String, selected: Boolean) -> Unit
) : ListAdapter<SplitTunnelApp, SplitTunnelAppAdapter.ViewHolder>(DIFF) {

    private val iconCache = LruCache<String, Drawable>(96)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSplitTunnelAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cancelIconLoad()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        private val binding: ItemSplitTunnelAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var iconJob: Job? = null
        private var boundPackage: String? = null

        fun bind(item: SplitTunnelApp) {
            boundPackage = item.packageName
            binding.appName.text = item.label
            binding.appPackage.text = item.packageName

            // Silence the listener while restoring state on a recycled row.
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = item.selected
            binding.appSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item.packageName, isChecked)
            }
            binding.root.setOnClickListener {
                binding.appSwitch.toggle()
            }

            loadIcon(item.packageName)
        }

        private fun loadIcon(packageName: String) {
            iconJob?.cancel()

            val cached = iconCache.get(packageName)
            if (cached != null) {
                binding.appIcon.setImageDrawable(cached)
                return
            }

            binding.appIcon.setImageDrawable(
                AppCompatResources.getDrawable(binding.root.context, R.drawable.shape_circle)
                    ?.mutate()
                    ?.apply { setTint(0xFF373843.toInt()) }
            )

            val pm = binding.root.context.applicationContext.packageManager
            iconJob = scope.launch(Dispatchers.IO) {
                val icon = try {
                    pm.getApplicationIcon(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                } catch (e: Exception) {
                    null
                }
                if (icon != null) {
                    iconCache.put(packageName, icon)
                }
                withContext(Dispatchers.Main) {
                    if (boundPackage == packageName && icon != null) {
                        binding.appIcon.setImageDrawable(icon)
                    }
                }
            }
        }

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SplitTunnelApp>() {
            override fun areItemsTheSame(oldItem: SplitTunnelApp, newItem: SplitTunnelApp) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: SplitTunnelApp, newItem: SplitTunnelApp) =
                oldItem == newItem
        }
    }
}
