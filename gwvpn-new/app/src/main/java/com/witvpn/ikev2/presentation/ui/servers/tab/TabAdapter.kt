package com.witvpn.ikev2.presentation.ui.servers.tab

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.Server.Companion.isAutoConnect
import com.witvpn.ikev2.presentation.utils.Util
import com.witvpn.ikev2.presentation.utils.inflate
import com.witvpn.ikev2.presentation.widget.SelectionItemView

class TabAdapter(
    private val click: (Server) -> Unit,
) : RecyclerView.Adapter<TabAdapter.ServerViewHolder>() {

    private var isPremium: Boolean = false
    private val servers: MutableList<Server> = mutableListOf()
    private var serversLoad: Map<String, Int> = emptyMap()
    private var protocolFilter: String = "IKEv2"

    fun updateData(servers: List<Server>, premium :Boolean, serversLoad: Map<String, Int> = emptyMap(), protocol: String = "IKEv2") {
        this.isPremium = premium
        this.serversLoad = serversLoad
        this.protocolFilter = protocol
        this.servers.clear()
        this.servers.addAll(servers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = parent.inflate(R.layout.item_vpn_server)
        return ServerViewHolder(view).also { holder ->
            view.setOnClickListener {
                click(servers[holder.layoutPosition])
            }
        }
    }

    override fun getItemCount(): Int =
        servers.size

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bindData(servers[position])
    }

    inner class ServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private var server: Server? = null

        fun bindData(server: Server) {
            this.server = server
            (itemView as? SelectionItemView)?.apply {
                setState(server)
                val status = if(Server.getDraftForProtocol(protocolFilter) == server){
                    SelectionItemView.Status.SELECTED
                } else if(server.premium == false || isPremium) {
                    SelectionItemView.Status.UNLOCKED
                } else {
                    SelectionItemView.Status.LOCKED
                }
                setStatus(status)
                
                // Set the load percentage
                setLoadPercentage(serversLoad[server.ipAddress])
            }
        }
    }
}