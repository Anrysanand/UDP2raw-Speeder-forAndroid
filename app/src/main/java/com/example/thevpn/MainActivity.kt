package com.example.thevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 確保工具已安裝
        installTool("speederv2")
        installTool("udp2raw")

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // 設定分頁適配器，現在只有 2 個分頁
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> ToolFragment.newInstance("speederv2")
                    else -> ToolFragment.newInstance("udp2raw")
                }
            }
        }

        // 修改 Tab 標題，移除 tinyfecVPN
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "UDPspeeder"
                else -> "udp2raw"
            }
        }.attach()
    }

    private fun installTool(name: String) {
        val file = File(filesDir, name)
        if (!file.exists()) {
            assets.open(name).use { input ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
            Runtime.getRuntime().exec("chmod 755 ${file.absolutePath}").waitFor()
        }
    }
}

// 分頁的具體邏輯
class ToolFragment : Fragment() {
    private lateinit var textLog: TextView
    private lateinit var toolName: String
    private lateinit var prefs: SharedPreferences

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(ForegroundService.EXTRA_LOG_MESSAGE)
            if (message != null) {
                textLog.append("$message\n")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "ToolParams"
        fun newInstance(toolName: String) = ToolFragment().apply {
            arguments = Bundle().apply { putString("NAME", toolName) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_tool, container, false)
        toolName = arguments?.getString("NAME") ?: ""

        val edit = root.findViewById<EditText>(R.id.editParams)
        val btnRun = root.findViewById<Button>(R.id.btnRun)
        val btnSave = root.findViewById<Button>(R.id.btnSave)
        val btnStop = root.findViewById<Button>(R.id.btnStop)
        textLog = root.findViewById<TextView>(R.id.textLog)

        // Load saved params or defaults
        loadParameters(edit)

        // Save button logic
        btnSave.setOnClickListener {
            saveParameters(edit.text.toString())
            textLog.append(getString(R.string.log_params_saved))
        }

        // Start button logic
        btnRun.setOnClickListener {
            textLog.text = "" // Clear log on run
            val params = edit.text.toString()
            val intent = Intent(requireContext(), ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_START
                putExtra("toolName", toolName)
                putExtra("params", params)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            textLog.text = getString(R.string.log_attempting_root_start, toolName)
        }

        // Stop button logic
        btnStop.setOnClickListener {
            val intent = Intent(requireContext(), ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_STOP
                putExtra("toolName", toolName)
            }
            requireContext().startService(intent)
            textLog.append("\n[系統] 已發送停止服務信號\n")
        }

        return root
    }

    private fun loadParameters(editText: EditText) {
        val defaultResId = when (toolName) {
            "speederv2" -> R.string.speederv2_defaults
            "udp2raw"   -> R.string.udp2raw_defaults
            else -> 0
        }
        val savedParams = prefs.getString(toolName, null)
        if (savedParams != null) {
            editText.setText(savedParams)
        } else if (defaultResId != 0) {
            editText.setText(getString(defaultResId))
        }
    }

    private fun saveParameters(params: String) {
        prefs.edit {
            putString(toolName, params)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ForegroundService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(logReceiver)
    }
}
