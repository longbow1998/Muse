package com.learn.antilazy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class WhitelistActivity : AppCompatActivity() {

    private data class AppItem(
        val packageName: String,
        val label: String,
        val icon: Drawable
    )

    private data class RowHolder(
        val icon: ImageView,
        val label: TextView,
        val packageName: TextView,
        val checked: CheckBox
    )

    private lateinit var search: EditText
    private lateinit var selectedCount: TextView
    private lateinit var usageWarning: TextView
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private val selected = mutableSetOf<String>()
    private val adapter = AppAdapter()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageUtils.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)

        search = findViewById(R.id.et_whitelist_search)
        selectedCount = findViewById(R.id.tv_whitelist_selected_count)
        usageWarning = findViewById(R.id.tv_whitelist_usage_warning)
        empty = findViewById(R.id.tv_whitelist_empty)
        list = findViewById(R.id.lv_whitelist_apps)

        selected.addAll(WhitelistStore.load(this))
        findViewById<ImageButton>(R.id.btn_whitelist_back).setOnClickListener { finish() }
        usageWarning.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (!selected.add(item.packageName)) selected.remove(item.packageName)
            MonitorService.setWhitelistedPackages(this, selected)
            adapter.notifyDataSetChanged()
            renderSelectedCount()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderSelectedCount()
        loadLauncherApps()
    }

    override fun onResume() {
        super.onResume()
        usageWarning.visibility = if (UsageStatsRepository.hasUsageAccess(this)) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun renderSelectedCount() {
        selectedCount.text = getString(R.string.whitelist_selected_count_fmt, selected.size)
    }

    private fun loadLauncherApps() {
        empty.setText(R.string.whitelist_loading)
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            val seen = HashSet<String>()
            val apps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
                AppItem(
                    packageName = pkg,
                    label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg,
                    icon = info.loadIcon(pm)
                )
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.replace(apps)
                empty.setText(R.string.whitelist_empty)
            }
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        private var all: List<AppItem> = emptyList()
        private var visible: List<AppItem> = emptyList()
        private var query = ""

        fun replace(items: List<AppItem>) {
            all = items
            applyFilter()
        }

        fun filter(value: String) {
            query = value.trim()
            applyFilter()
        }

        private fun applyFilter() {
            visible = if (query.isEmpty()) {
                all
            } else {
                all.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visible.size

        override fun getItem(position: Int): AppItem = visible[position]

        override fun getItemId(position: Int): Long =
            visible[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: RowHolder
            if (convertView == null) {
                view = layoutInflater.inflate(R.layout.row_whitelist_app, parent, false)
                holder = RowHolder(
                    icon = view.findViewById(R.id.iv_whitelist_app_icon),
                    label = view.findViewById(R.id.tv_whitelist_app_label),
                    packageName = view.findViewById(R.id.tv_whitelist_package),
                    checked = view.findViewById(R.id.cb_whitelist_selected)
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as RowHolder
            }

            val item = getItem(position)
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.packageName.text = item.packageName
            holder.checked.isChecked = item.packageName in selected
            return view
        }
    }
}
