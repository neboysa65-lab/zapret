package com.witvpn.ikev2.presentation.ui.vless

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.utils.SubscriptionNameFlags
import com.witvpn.ikev2.presentation.utils.Util
import com.witvpn.ikev2.presentation.utils.inflate
import com.witvpn.ikev2.presentation.widget.SelectionItemView
import com.witvpn.ikev2.vless.VlessConfig
import com.witvpn.ikev2.vless.VlessManager

class VlessAdapter(
    private val click: (VlessConfig) -> Unit,
) : RecyclerView.Adapter<VlessAdapter.VlessViewHolder>() {

    private val servers: MutableList<VlessConfig> = mutableListOf()
    private var serversLoad: Map<String, Int> = emptyMap()

    fun updateData(servers: List<VlessConfig>, serversLoad: Map<String, Int> = emptyMap()) {
        this.serversLoad = serversLoad
        this.servers.clear()
        this.servers.addAll(servers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VlessViewHolder {
        val view = parent.inflate(R.layout.item_vpn_server)
        return VlessViewHolder(view).also { holder ->
            view.setOnClickListener {
                val pos = holder.layoutPosition
                if (pos in servers.indices) {
                    click(servers[pos])
                }
            }
        }
    }

    override fun getItemCount(): Int = servers.size

    override fun onBindViewHolder(holder: VlessViewHolder, position: Int) {
        holder.bindData(servers[position])
    }

    inner class VlessViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        fun bindData(server: VlessConfig) {
            (itemView as? SelectionItemView)?.apply {
                val parsed = SubscriptionNameFlags.parse(server.name)
                // Флаг из emoji в имени подписки; иначе глобус
                val flagRes = parsed.countryCode
                    ?.let { Util.getResId(it) }
                    ?.takeIf { it != 0 && it != -1 }
                    ?: R.drawable.ic_globe
                setFlag(flagRes)
                // Имя без emoji-флага; для Hysteria2 — маленькое лого слева от текста
                val titleIcon = if (server.isHysteria2()) R.drawable.ic_hysteria_logo else 0
                setTitle(parsed.displayName, leadingIconRes = titleIcon)
                setDescription("")

                val sel = VlessManager.selectedServer
                val status = if (
                    sel != null &&
                    sel.uuid == server.uuid &&
                    sel.address == server.address &&
                    sel.port == server.port &&
                    sel.protocol == server.protocol
                ) {
                    SelectionItemView.Status.SELECTED
                } else {
                    SelectionItemView.Status.UNLOCKED
                }
                setStatus(status)

                val load = serversLoad[server.address]
                setLoadPercentage(load)
            }
        }
    }
}
