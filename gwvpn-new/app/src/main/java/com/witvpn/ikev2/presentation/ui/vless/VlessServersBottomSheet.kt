package com.witvpn.ikev2.presentation.ui.vless

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.ui.servers.ServersViewModel
import com.witvpn.ikev2.presentation.utils.show
import com.witvpn.ikev2.vless.VlessConfig
import com.witvpn.ikev2.vless.VlessManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VlessServersBottomSheet() : BottomSheetDialogFragment() {

    var onServerSelected: ((VlessConfig) -> Unit)? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var closeButton: ImageView
    
    private val viewModel: ServersViewModel by activityViewModels()

    private val adapter by lazy { 
        VlessAdapter { server ->
            val previousServer = VlessManager.selectedServer
            VlessManager.selectedServer = server
            onServerSelected?.invoke(server)
            dismiss()
        } 
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_servers2, container, false)
        
        // Customize the view for Vless context since we are reusing layout
        view.findViewById<View>(R.id.tabLayout).visibility = View.GONE
        view.findViewById<View>(R.id.viewPager).visibility = View.GONE
        
        // We need to inject RecyclerView manually since we are reusing a layout 
        // that was designed for ViewPager + Tabs. 
        // But to be safe and "not change existing code", we should probably 
        // instantiate a RecyclerView programmatically and add it to the LinearLayout 
        // inside fragment_servers2.xml structure or just use a simple separate layout if allowed.
        // HOWEVER, user said "use same design". 
        
        // Let's create a cleaner approach: use a new layout file that mimics the structure 
        // but is specific for this list, OR modify the view hierarchy dynamically.
        // Let's modify hierarchy dynamically to be safe.
        
        val contentLayout = view.findViewById<ViewGroup>(R.id.viewPager).parent as ViewGroup
        val context = requireContext()
        
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            context.getDrawable(R.drawable.div_height_10)?.let { drawable ->
                addItemDecoration(
                    DividerItemDecoration(context, RecyclerView.VERTICAL).apply {
                        setDrawable(drawable)
                    }
                )
            }
        }
        
        // Replace ViewPager with RecyclerView in the layout
        val index = contentLayout.indexOfChild(view.findViewById(R.id.viewPager))
        contentLayout.removeViewAt(index)
        contentLayout.addView(recyclerView, index, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        progressBar = view.findViewById(R.id.progress)
        closeButton = view.findViewById(R.id.btnLeft)
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView.adapter = adapter
        
        closeButton.setOnClickListener { dismiss() }

        viewModel.serversLoadMap.observe(viewLifecycleOwner) { loadMap ->
            android.util.Log.d("VlessLoad", "Observed loadMap size: ${loadMap.size}, keys: ${loadMap.keys}")
            if (VlessManager.vlessServers.isNotEmpty()) {
                adapter.updateData(VlessManager.vlessServers, loadMap)
            }
        }
        
        viewModel.fetchLoad()
        
        loadServers()
    }

    private fun loadServers() {
        if (VlessManager.vlessServers.isNotEmpty()) {
            adapter.updateData(VlessManager.vlessServers, viewModel.serversLoadMap.value ?: emptyMap())
            progressBar.show(false)
        } else {
            progressBar.show(true)
            VlessManager.fetchSubscription(requireContext()) { success ->
                if (!isAdded) return@fetchSubscription
                progressBar.show(false)
                if (success) {
                    android.util.Log.d("VlessLoad", "fetchSubscription success. Servers: ${VlessManager.vlessServers.size}, LoadMap keys: ${viewModel.serversLoadMap.value?.keys}")
                    adapter.updateData(VlessManager.vlessServers, viewModel.serversLoadMap.value ?: emptyMap())
                }
            }
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }
}
