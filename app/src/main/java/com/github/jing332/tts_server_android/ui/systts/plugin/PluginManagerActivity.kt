package com.github.jing332.tts_server_android.ui.systts.plugin

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.view.menu.MenuBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import com.drake.brv.BindingAdapter
import com.drake.brv.listener.DefaultItemTouchCallback
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.drake.net.utils.withDefault
import com.drake.net.utils.withMain
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.constant.KeyConst
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.plugin.Plugin
import com.github.jing332.tts_server_android.databinding.SysttsPlguinListItemBinding
import com.github.jing332.tts_server_android.databinding.SysttsPluginManagerActivityBinding
import com.github.jing332.tts_server_android.ui.base.BackActivity
import com.github.jing332.tts_server_android.ui.systts.ConfigExportBottomSheetFragment
import com.github.jing332.tts_server_android.ui.view.AppDialogs
import com.github.jing332.tts_server_android.util.MyTools
import com.github.jing332.tts_server_android.util.clickWithThrottle
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class PluginManagerActivity : BackActivity() {
    val binding by lazy { SysttsPluginManagerActivityBinding.inflate(layoutInflater) }
    val vm: PluginManagerViewModel by viewModels()

    @Suppress("DEPRECATION")
    private val startForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.apply {
                getParcelableExtra<Plugin>(KeyConst.KEY_DATA)?.let {
                    appDb.pluginDao.insert(it)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val brv = binding.rv.linear().setup {
            addType<PluginModel>(R.layout.systts_plguin_list_item)
            onCreate {
                getBinding<SysttsPlguinListItemBinding>().apply {
                    btnDelete.clickWithThrottle {
                        val model = getModel<PluginModel>()
                        AppDialogs.displayDeleteDialog(
                            this@PluginManagerActivity, model.title
                        ) { appDb.pluginDao.delete(model.data) }
                    }
                    btnEdit.clickWithThrottle {
                        startForResult.launch(
                            Intent(
                                this@PluginManagerActivity,
                                PluginEditorActivity::class.java
                            ).apply { putExtra(KeyConst.KEY_DATA, getModel<PluginModel>().data) })
                    }
                    cbSwitch.setOnClickListener {
                        appDb.pluginDao.update(getModel<PluginModel>().data.copy(isEnabled = cbSwitch.isChecked))
                    }
                }
            }

            itemTouchHelper = ItemTouchHelper(object : DefaultItemTouchCallback() {
                override fun onDrag(
                    source: BindingAdapter.BindingViewHolder,
                    target: BindingAdapter.BindingViewHolder
                ) {
                    models?.filterIsInstance<PluginModel>()?.let { models ->
                        appDb.pluginDao.update(*models.mapIndexed { index, t ->
                            t.data.apply { order = index }
                        }.toTypedArray())
                    }
                }
            })

        }

        lifecycleScope.launch {
            appDb.pluginDao.flowAll().conflate().collect { list ->
                val models = list.map { PluginModel(it) }
                withMain {
                    if (brv.models == null) brv.models = models
                    else withDefault { brv.setDifferModels(models) }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.plugin_manager, menu)
        return super.onCreateOptionsMenu(menu)
    }

    @SuppressLint("RestrictedApi")
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
            menu.setGroupDividerEnabled(true)
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> {
                startForResult.launch(Intent(this, PluginEditorActivity::class.java))
            }

            R.id.menu_shortcut -> {
                MyTools.addShortcut(
                    this,
                    getString(R.string.plugin_manager),
                    "plugin_manager",
                    R.drawable.ic_plugin,
                    Intent(this, PluginManagerActivity::class.java)
                )
            }

            R.id.menu_export -> {
                val fragment =
                    ConfigExportBottomSheetFragment({ vm.exportConfig() }, { "ttsrv-plugins.json" })
                fragment.show(supportFragmentManager, ConfigExportBottomSheetFragment.TAG)
            }

            R.id.menu_import -> {
                val fragment = ImportConfigBottomSheetFragment()
                fragment.show(supportFragmentManager, ImportConfigBottomSheetFragment.TAG)
            }
        }
        return super.onOptionsItemSelected(item)
    }
}