package com.github.jing332.tts_server_android.ui.base.import1

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isInvisible
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.net.Net
import com.drake.net.okhttp.trustSSLCertificate
import com.drake.net.utils.withIO
import com.drake.net.utils.withMain
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.databinding.BaseConfigImportItemBinding
import com.github.jing332.tts_server_android.databinding.SysttsBaseImportConfigBottomSheetBinding
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.FilePickerActivity
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import com.github.jing332.tts_server_android.ui.view.widget.WaitDialog
import com.github.jing332.tts_server_android.util.ClipboardUtils
import com.github.jing332.tts_server_android.util.FileUtils.readAllText
import com.github.jing332.tts_server_android.util.clickWithThrottle
import com.github.jing332.tts_server_android.util.dp
import com.github.jing332.tts_server_android.util.setMarginMatchParent
import com.github.jing332.tts_server_android.util.toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response

abstract class BaseImportConfigBottomSheetFragment(
    @StringRes
    protected var title: Int = R.string.import_config,
    var fileUri: Uri? = null,
) :
    BottomSheetDialogFragment() {

    private val binding by lazy { SysttsBaseImportConfigBottomSheetBinding.inflate(layoutInflater) }

    companion object {
        const val SRC_CLIPBOARD = 1
        const val SRC_FILE = 2
        const val SRC_URL = 3
    }

    private val sourceType: Int
        get() {
            return when (binding.groupSource.checkedButtonId) {
                R.id.btn_src_clipboard -> SRC_CLIPBOARD
                R.id.btn_src_local_file -> SRC_FILE
                R.id.btn_src_url -> SRC_URL
                else -> SRC_CLIPBOARD
            }
        }

    protected val waitDialog by lazy { WaitDialog(requireContext()) }

    fun setTopContentView(view: View) {
        binding.content.removeAllViews()
        binding.content.addView(view)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.setText(title)
        (view.parent as ViewGroup).setMarginMatchParent()

        binding.apply {
            groupSource.check(R.id.btn_src_clipboard)
            groupSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
                when (checkedId) {
                    R.id.btn_src_url -> tilUrl.isInvisible = !isChecked
                    R.id.btn_src_local_file -> tilFilePath.isInvisible = !isChecked
                }
            }

            tilFilePath.setEndIconOnClickListener {
                fileSelection.launch(
                    FilePickerActivity.RequestSelectFile(listOf("application/json", "text/*"))
                )
            }

            btnImport.clickWithThrottle {
                waitDialog.show()
                lifecycleScope.launch(Dispatchers.Main) {
                    val json = try {
                        withIO {
                            binding.tilFilePath.editText?.tag as? String
                                ?: readConfig(
                                    sourceType,
                                    binding.tilUrl.editText?.text.toString(),
                                    fileUri,
                                )
                        }
                    } catch (e: Exception) {
                        requireContext().displayErrorDialog(e)
                        return@launch
                    } finally {
                        waitDialog.dismiss()
                    }

                    kotlin.runCatching {
                        if (checkJson(json))
                            onImport(json)
                        else
                            throw Exception(getString(R.string.format_error))
                    }.onFailure {
                        requireContext().displayErrorDialog(it)
                    }
                }
            }
        }

        fileUri?.let {
            binding.groupSource.check(R.id.btn_src_local_file)
            binding.tilFilePath.editText?.setText(fileUri.toString())
            binding.btnImport.performClick()
        }
    }

    abstract fun onImport(json: String)

    private suspend fun readConfig(
        src: Int,
        url: String? = null,
        uri: Uri? = null
    ): String {
        return when (src) {
            SRC_URL -> withContext(Dispatchers.IO) {
                val resp: Response = Net.get(url.toString()) {
                    setClient { trustSSLCertificate() }
                }.execute()
                val str = resp.body?.string()
                if (resp.isSuccessful && !str.isNullOrBlank()) {
                    return@withContext str
                } else {
                    throw Exception("GET $url failed: code=${resp.code}, message=${resp.message}, body=${str}")
                }
            }

            SRC_FILE -> withContext(Dispatchers.IO) { uri?.readAllText(requireContext()) ?: throw  Exception("file uri is null!") }

            else -> withMain { ClipboardUtils.text.toString() }
        }
    }

    private val fileSelection =
        registerForActivityResult(AppActivityResultContracts.filePickerActivity()) {
            if (it.second != null) {
                // 不知为何 必须在这里读取才有权限
                fileUri = it?.second
//                binding.tilFilePath.editText?.tag = it?.second?.readAllText(requireContext())
                binding.tilFilePath.editText?.setText(it.second.toString())
            }
        }

    fun displayListSelectDialog(
        list: List<ConfigItemModel>,
        onSelectedList: (list: List<Any>) -> Unit
    ) {
        val rv = RecyclerView(requireContext()).apply {
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            linear().setup {
                addType<ConfigItemModel>(R.layout.base_config_import_item)
                onCreate {
                    getBinding<BaseConfigImportItemBinding>().apply {
                        itemView.setOnClickListener {
                            checkBox.isChecked = !checkBox.isChecked
                        }
                    }
                }
            }.models = list
        }
        rv.setPadding(16.dp)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_import)
            .setView(rv)
            .setPositiveButton(R.string.import_config) { _, _ ->
                onSelectedList.invoke(
                    rv.models!!.filter { it is ConfigItemModel && it.isEnabled }
                        .map { (it as ConfigItemModel).data }
                )
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    open fun checkJson(json: String): Boolean = true

}