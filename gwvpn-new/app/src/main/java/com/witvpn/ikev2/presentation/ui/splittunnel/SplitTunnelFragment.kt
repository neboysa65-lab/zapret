package com.witvpn.ikev2.presentation.ui.splittunnel

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentSplitTunnelBinding
import com.witvpn.ikev2.features.splittunnel.SplitTunnelMode
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.utils.hideSoftKeyboard
import com.witvpn.ikev2.presentation.utils.showKeyboard
import dagger.hilt.android.AndroidEntryPoint

/**
 * Split Tunneling screen. The user picks one of three modes:
 *  - Off — every application is routed through the VPN tunnel (default);
 *  - Only selected — only the ticked applications use the tunnel;
 *  - All except selected — the ticked applications bypass the tunnel.
 *
 * The choice is persisted in [com.witvpn.ikev2.features.splittunnel.SplitTunnelStore]
 * and is applied by every VPN backend (IKEv2, VLESS/Hysteria2, AmneziaWG) on the
 * next tunnel establishment.
 *
 * Expanded (full-screen list) UX: the header block (title, description, mode
 * card) can be collapsed so the app list takes the whole screen. The state is
 * entered automatically when the user starts scrolling the list or focuses
 * the search field, and is left explicitly — via the back arrow that appears
 * to the left of the search field or via the system Back gesture. Closing
 * the keyboard does NOT collapse the list back: the keyboard visibility and
 * the expanded state are independent, so after searching the user can hide
 * the keyboard and keep picking apps on the full screen.
 * See [initSearchUx] and [setExpanded].
 */
@AndroidEntryPoint
class SplitTunnelFragment : BaseFragment<FragmentSplitTunnelBinding>(R.layout.fragment_split_tunnel) {

    private val viewModel: SplitTunnelViewModel by viewModels()

    private var adapter: SplitTunnelAppAdapter? = null

    private val radioInactiveTint = ColorStateList.valueOf(0xFF5A5F6A.toInt())

    /** Last known soft-keyboard visibility. */
    private var imeVisible = false

    /**
     * Whether the screen is in the expanded (full-screen list) state: the
     * header is collapsed, the search row is pinned to the top with a back
     * arrow, and the system Back gesture collapses the state instead of
     * leaving the screen. Independent from [imeVisible] — hiding the
     * keyboard keeps the list full-screen.
     */
    private var expanded = false

    /** Consumes the system Back gesture while [expanded] is true. */
    private var backCallback: OnBackPressedCallback? = null

    /**
     * The view the keyboard watcher is attached to. Kept as a plain reference
     * because the binding is auto-cleared before onDestroyView() runs (see
     * AutoClearedValue) and the listener must still be detached there.
     */
    private var keyboardWatcherRoot: View? = null
    private var keyboardWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun initBinding(view: View): FragmentSplitTunnelBinding {
        return FragmentSplitTunnelBinding.bind(view)
    }

    override fun initView() {
        binding.btnLeft.setOnClickListener {
            try {
                findNavController().popBackStack()
            } catch (e: Exception) {
                // Navigation state may be invalid
            }
        }

        // Back arrow next to the search field: leaves the expanded
        // (full-screen list) state and restores the header.
        binding.btnExitExpanded.setOnClickListener { exitExpandedState() }

        // The system Back gesture must do the same instead of leaving the
        // whole screen. When the keyboard is open, Back is consumed by the
        // IME first (closing it), so the callback fires on the next press.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitExpandedState()
            }
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
        }

        binding.modeOff.setOnClickListener { viewModel.setMode(SplitTunnelMode.OFF) }
        binding.modeOnly.setOnClickListener { viewModel.setMode(SplitTunnelMode.ONLY_SELECTED) }
        binding.modeExcept.setOnClickListener { viewModel.setMode(SplitTunnelMode.EXCEPT_SELECTED) }

        adapter = SplitTunnelAppAdapter(viewLifecycleOwner.lifecycleScope) { packageName, selected ->
            viewModel.setSelected(packageName, selected)
        }
        binding.appsList.layoutManager = LinearLayoutManager(requireContext())
        binding.appsList.adapter = adapter
        binding.appsList.itemAnimator = null

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.clearSearch.isVisible = text.isNotEmpty()
                viewModel.setQuery(text)
            }
        })
        binding.clearSearch.setOnClickListener {
            binding.searchInput.setText("")
        }

        initSearchUx()
    }

    /**
     * Keyboard- and scroll-aware behaviour of the search block:
     *  1. the root view absorbs the IME inset, so on edge-to-edge devices
     *     (Android 15+ / targetSdk 35, where adjustResize no longer resizes
     *     the window) the content shrinks instead of being covered;
     *  2. focusing the search field (or any keyboard appearance) switches
     *     the screen into the expanded state — the search field is pinned to
     *     the top and the list gets all the remaining space;
     *  3. the keyboard is dismissed by scrolling the list, tapping outside
     *     the search field, or pressing the IME search action — but the list
     *     stays full-screen; collapsing back is an explicit action (the back
     *     arrow next to the field or the system Back gesture). Tapping an app
     *     row keeps the keyboard: the user can tick several found apps in a
     *     row without re-opening it;
     *  4. starting to scroll the list on the normal screen also enters the
     *     expanded state, so browsing the full app catalog immediately gives
     *     the whole screen to the list.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initSearchUx() {
        // 1. Consume the IME inset as bottom padding. On devices where the
        // system still resizes the window (adjustResize before the enforced
        // edge-to-edge) the inset arrives here as 0, so the padding is only
        // applied when the system does NOT resize us — never twice.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (v.paddingBottom != imeInset) {
                v.updatePadding(bottom = imeInset)
            }
            if (imeInset > 0) {
                onImeVisibleChanged(true)
            }
            insets
        }

        // 2. Visibility watcher that works on every Android version and with
        // every keyboard height: compares the window's visible frame against
        // the decor height. It is the authoritative source of the "keyboard
        // hidden" signal — on devices that resize the window the IME insets
        // are consumed before they reach this fragment, so they cannot be
        // relied upon for hiding.
        val root = binding.root
        val watcher = ViewTreeObserver.OnGlobalLayoutListener {
            val decor = activity?.window?.decorView ?: return@OnGlobalLayoutListener
            val decorHeight = decor.height
            if (decorHeight == 0) return@OnGlobalLayoutListener
            val frame = Rect()
            decor.getWindowVisibleDisplayFrame(frame)
            // frame.height() (not frame.bottom) keeps the math correct in
            // multi-window, where the visible frame uses display coordinates.
            val covered = decorHeight - frame.height()
            val byFrame = covered > decorHeight * IME_VISIBLE_FRACTION
            // The root window insets are OR-ed in so the two sources can
            // never fight each other while the IME animation is running.
            val byInsets = ViewCompat.getRootWindowInsets(root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            onImeVisibleChanged(byFrame || byInsets)
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(watcher)
        keyboardWatcherRoot = root
        keyboardWatcher = watcher

        // 3. Collapse the header as soon as the field gains focus instead of
        // waiting for the keyboard animation to report new insets. If the
        // keyboard never shows up (hardware keyboard), the watcher above
        // reverts the state on the next layout pass.
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onImeVisibleChanged(true)
        }
        // The whole rounded container acts as the search field's hit area.
        binding.searchContainer.setOnClickListener {
            binding.searchInput.requestFocus()
            context.showKeyboard(binding.searchInput)
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dismissKeyboard()
                true
            } else {
                false
            }
        }

        // 4. Scrolling the list dismisses the keyboard (the user is browsing
        // the results now) and expands the list to the full screen — both on
        // the normal screen and during a search.
        binding.appsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (imeVisible) {
                        dismissKeyboard()
                    }
                    setExpanded(true)
                }
            }
        })
        // …taps the empty area of the list below the last row…
        binding.appsList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN && imeVisible &&
                    rv.findChildViewUnder(e.x, e.y) == null
                ) {
                    dismissKeyboard()
                }
                return false
            }
        })
        // …or taps anywhere else outside the search field. The listener fires
        // only for touches that no interactive child view has consumed.
        binding.root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && imeVisible) {
                dismissKeyboard()
            }
            false
        }
    }

    /** Deduplicated IME visibility change; safe to call from any watcher. */
    private fun onImeVisibleChanged(visible: Boolean) {
        if (imeVisible == visible) return
        imeVisible = visible
        if (view == null) return
        if (visible) {
            // Opening the keyboard always switches to the full-screen list,
            // so the query and the live-filtered results are seen together.
            setExpanded(true)
        } else {
            // The keyboard was closed by any means (Back, IME action, scroll):
            // release the focus so the cursor does not linger in the field.
            // The list intentionally STAYS full-screen — collapsing back is
            // an explicit action (back arrow / system Back gesture).
            binding.searchInput.clearFocus()
        }
    }

    /**
     * Switches the expanded (full-screen list) state. While expanded the
     * header block is hidden, the search row is pinned to the top with a
     * back arrow next to it, and the system Back gesture collapses the state
     * instead of leaving the screen.
     */
    private fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value) return
        expanded = value
        backCallback?.isEnabled = value
        if (view == null) return
        applyHeaderState(animate)
    }

    /** Leaves the full-screen list: closes the keyboard, restores the header. */
    private fun exitExpandedState() {
        dismissKeyboard()
        setExpanded(false)
    }

    /**
     * Shows/hides the block above the search field. In the expanded state
     * the block is collapsed: the search row moves to the top of the visible
     * area (gaining a back arrow) and the app list below it takes the rest
     * of the space.
     */
    private fun applyHeaderState(animate: Boolean) {
        val selecting = (viewModel.mode.value ?: SplitTunnelMode.OFF) != SplitTunnelMode.OFF
        val collapsed = expanded && selecting
        val headerVisible = !collapsed
        if (binding.collapsingHeader.isVisible == headerVisible &&
            binding.btnExitExpanded.isVisible == collapsed
        ) {
            return
        }
        if (animate) {
            val transition = AutoTransition()
            transition.setOrdering(TransitionSet.ORDERING_TOGETHER)
            transition.setDuration(HEADER_TRANSITION_MS)
            // The list is resized by the same transition; its rows must not
            // fade in and out one by one while that happens.
            transition.excludeChildren(binding.appsList, true)
            TransitionManager.beginDelayedTransition(binding.contentContainer, transition)
        }
        binding.collapsingHeader.isVisible = headerVisible
        binding.btnExitExpanded.isVisible = collapsed
    }

    private fun dismissKeyboard() {
        activity.hideSoftKeyboard()
        binding.searchInput.clearFocus()
    }

    override fun initObserve() {
        viewModel.mode.observe(viewLifecycleOwner) { render() }

        viewModel.isLoading.observe(viewLifecycleOwner) { render() }

        viewModel.apps.observe(viewLifecycleOwner) { apps ->
            adapter?.submitList(apps)
            render()
        }

        viewModel.onlySelectionEmpty.observe(viewLifecycleOwner) { empty ->
            binding.onlyEmptyNotice.isVisible = empty == true
        }

        viewModel.vpnActive.observe(viewLifecycleOwner) { active ->
            binding.reconnectNotice.isVisible = active == true
        }
    }

    /**
     * Single place that reconciles the whole screen with the current state
     * (mode, loading flag, filtered list). Keeping it in one function avoids
     * visibility races between individual observers, including right after a
     * configuration change when all observers re-fire in a row.
     */
    private fun render() {
        val mode = viewModel.mode.value ?: SplitTunnelMode.OFF
        val loading = viewModel.isLoading.value == true
        val apps = viewModel.apps.value
        val selecting = mode != SplitTunnelMode.OFF

        bindRadio(binding.modeOffRadio, mode == SplitTunnelMode.OFF)
        bindRadio(binding.modeOnlyRadio, mode == SplitTunnelMode.ONLY_SELECTED)
        bindRadio(binding.modeExceptRadio, mode == SplitTunnelMode.EXCEPT_SELECTED)

        binding.appsHeader.isVisible = selecting
        if (selecting) {
            binding.appsHeader.setText(
                if (mode == SplitTunnelMode.ONLY_SELECTED) {
                    R.string.split_tunnel_apps_via_vpn
                } else {
                    R.string.split_tunnel_apps_bypass
                }
            )
        }

        binding.searchRow.isVisible = selecting
        binding.appsList.isVisible = selecting
        binding.offState.isVisible = !selecting
        binding.progress.isVisible = selecting && loading
        binding.emptyView.isVisible = selecting && !loading && apps != null && apps.isEmpty()

        // Switching to OFF leaves the expanded state: the list is gone, so
        // the full-screen mode (and its Back interception) makes no sense.
        if (!selecting && expanded) {
            setExpanded(false, animate = false)
        }

        // Keep the collapsing header consistent with the mode (e.g. the
        // keyboard cannot stay "expanded over" the OFF placeholder).
        applyHeaderState(animate = false)
    }

    private fun bindRadio(view: ImageView, checked: Boolean) {
        if (checked) {
            view.setImageResource(R.drawable.ic_baseline_check_circle_outline_24)
            view.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.blue)
            )
        } else {
            view.setImageResource(R.drawable.ic_baseline_radio_button_unchecked_24)
            view.imageTintList = radioInactiveTint
        }
    }

    override fun onResume() {
        super.onResume()
        // Vyom/AWG state is polled, IKEv2 comes via a listener — refresh on return.
        viewModel.refreshVpnActive()
    }

    override fun onDestroyView() {
        // NOTE: binding is auto-cleared before onDestroyView() is called (see
        // AutoClearedValue), so only fragment-level references are released here.
        // The adapter's icon-load jobs are tied to viewLifecycleOwner.lifecycleScope
        // and are cancelled automatically.
        keyboardWatcher?.let { watcher ->
            keyboardWatcherRoot?.viewTreeObserver
                ?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(watcher)
        }
        keyboardWatcher = null
        keyboardWatcherRoot = null
        imeVisible = false
        expanded = false
        // Registered with viewLifecycleOwner, so it is removed automatically;
        // only the reference is released here.
        backCallback = null
        adapter = null
        super.onDestroyView()
    }

    private companion object {
        /**
         * Fraction of the window height that must be covered before the
         * overlap is treated as the keyboard. Small enough for compact
         * keyboards on tall screens, large enough to never confuse a
         * navigation bar (3-button included) with the IME.
         */
        private const val IME_VISIBLE_FRACTION = 0.15f

        /** Duration of the header collapse/expand animation. */
        private const val HEADER_TRANSITION_MS = 180L
    }
}
