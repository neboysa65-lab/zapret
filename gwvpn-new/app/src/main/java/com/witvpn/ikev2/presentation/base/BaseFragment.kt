package com.witvpn.ikev2.presentation.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.witvpn.ikev2.presentation.utils.autoCleared

abstract class BaseFragment<VB : ViewBinding>(@LayoutRes layoutRes: Int) : Fragment(layoutRes) {
    var binding by autoCleared<VB>()

    private val baseViewModel: BaseViewModel? by lazy {
        return@lazy initViewModel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        binding = initBinding(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObserve()
    }

    abstract fun initBinding(view: View): VB
    abstract fun initView()

    open fun initViewModel(): BaseViewModel? = null
    open fun initObserve() {}
}