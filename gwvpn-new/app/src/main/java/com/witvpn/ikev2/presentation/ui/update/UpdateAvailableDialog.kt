package com.witvpn.ikev2.presentation.ui.update

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.DialogUpdateAvailableBinding
import com.witvpn.ikev2.presentation.utils.AppUpdatePrompt
import com.witvpn.ikev2.presentation.utils.UpdateInfo
import com.witvpn.ikev2.presentation.utils.UpdateKind

/** Живёт, пока жива MainActivity (переживает пересоздание). */
class UpdatePromptViewModel : ViewModel() {
    /** Версия, для которой в этом запуске нажали «Позже» (или ушли в магазин). */
    var dismissedTarget: String? = null
}

class UpdateAvailableDialog : DialogFragment() {

    companion object {
        const val TAG = "UpdateAvailableDialog"
        private const val ARG_KIND = "kind"
        private const val ARG_INSTALLED = "installed"
        private const val ARG_TARGET = "target"
        private const val ARG_URL = "url"
        private const val ARG_NOTES = "notes"

        fun newInstance(info: UpdateInfo) = UpdateAvailableDialog().apply {
            arguments = bundleOf(
                ARG_KIND to info.kind.name,
                ARG_INSTALLED to info.installedVersion,
                ARG_TARGET to info.targetVersion,
                ARG_URL to info.updateUrl,
                ARG_NOTES to info.notes,
            )
        }
    }

    private val promptViewModel: UpdatePromptViewModel by activityViewModels()
    private var binding: DialogUpdateAvailableBinding? = null

    /** Окно уже закрывается — проверка не должна считать его показанным. */
    var isClosing = false
        private set

    /** Что показывает окно — для сравнения со свежим конфигом. */
    fun shownInfo(): UpdateInfo? {
        val args = arguments ?: return null
        val kind = runCatching { UpdateKind.valueOf(args.getString(ARG_KIND).orEmpty()) }.getOrNull()
            ?: return null
        return UpdateInfo(
            kind = kind,
            installedVersion = args.getString(ARG_INSTALLED).orEmpty(),
            targetVersion = args.getString(ARG_TARGET).orEmpty(),
            updateUrl = args.getString(ARG_URL).orEmpty(),
            notes = args.getString(ARG_NOTES).orEmpty(),
        )
    }

    private fun rememberDismissed() {
        promptViewModel.dismissedTarget = arguments?.getString(ARG_TARGET)
    }

    /** Закрыть без отметки «Позже» (конфиг изменился). */
    fun close() {
        isClosing = true
        dismissAllowingStateLoss()
    }

    private val required: Boolean
        get() = arguments?.getString(ARG_KIND) == UpdateKind.REQUIRED.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.UpdateDialogTheme)
        isCancelable = !required
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply { setCanceledOnTouchOutside(false) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = DialogUpdateAvailableBinding.inflate(inflater, container, false)
        binding = b
        val args = requireArguments()

        b.tvTitle.setText(if (required) R.string.update_title_forced else R.string.update_title)
        b.tvMessage.setText(if (required) R.string.update_message_forced else R.string.update_message)

        val installed = args.getString(ARG_INSTALLED).orEmpty()
        val target = args.getString(ARG_TARGET).orEmpty()
        if (installed.isNotBlank() && target.isNotBlank()) {
            b.tvVersion.text = getString(R.string.update_version_change, installed, target)
            b.tvVersion.visibility = View.VISIBLE
        } else {
            b.tvVersion.visibility = View.GONE
        }

        val notes = args.getString(ARG_NOTES).orEmpty()
        b.notesBlock.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
        b.tvNotes.text = notes

        b.btnLater.visibility = if (required) View.GONE else View.VISIBLE
        b.btnLater.setOnClickListener {
            rememberDismissed()
            close()
        }
        b.btnUpdate.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (!AppUpdatePrompt.openStore(ctx, args.getString(ARG_URL))) {
                Toast.makeText(ctx, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Обязательное обновление не закрываем: вернулся без обновления — окно на месте
            if (!required) {
                rememberDismissed()
                close()
            }
        }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        rememberDismissed()
    }

    override fun onDismiss(dialog: DialogInterface) {
        isClosing = true
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
