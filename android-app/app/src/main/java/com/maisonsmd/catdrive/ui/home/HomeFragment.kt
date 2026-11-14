package com.maisonsmd.catdrive.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.maisonsmd.catdrive.MainActivity
import com.maisonsmd.catdrive.R
import com.maisonsmd.catdrive.databinding.FragmentHomeBinding
import com.maisonsmd.catdrive.lib.BitmapHelper
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.ui.ActivityViewModel
import com.maisonsmd.catdrive.utils.ExecutionLog
import com.maisonsmd.catdrive.utils.ServiceManager
import timber.log.Timber


class HomeFragment : Fragment() {
    private var mUiBinding: FragmentHomeBinding? = null
    private var mDebugImage = false
    private var mDiagnosticTextView: TextView? = null
    private val exportLogRequest = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            ExecutionLog.exportTo(requireContext().contentResolver, uri) { result ->
                if (!isAdded) return@exportTo
                val message = if (result.isSuccess) {
                    getString(R.string.execution_log_exported)
                } else {
                    getString(
                        R.string.execution_log_export_failed,
                        result.exceptionOrNull()?.message ?: "Unknown error"
                    )
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val binding get() = mUiBinding!!

    private fun displayNavigationData(data: NavigationData?) {
        val bitmap =
            if (!mDebugImage) data?.actionIcon?.bitmap
            else resources.getDrawable(R.drawable.roundabout).toBitmap()

        binding.imgTurnIcon.setImageBitmap(bitmap)
        /*
        val bh = BitmapHelper()
        val compressed = bh.compressBitmap(bitmap, Size(32, 32))
        binding.imgScaled.setImageDrawable(
            BitmapHelper.AliasingDrawableWrapper(
                bitmap?.scale(32, 32, false)?.toDrawable(resources)
            )
        )
        binding.imgFinal.setImageDrawable(BitmapHelper.AliasingDrawableWrapper(compressed.toDrawable(resources)))
        // Timber.e(BitmapHelper().toBase64(compressed))
        */

        if (data == null) {
            binding.txtRoadName.text = "---"
            binding.txtRoadAdditionalInfo.text = "---"
            binding.txtDistance.text = "---"
            binding.txtEta.text = "---"
            return
        }

        binding.txtRoadName.text = data.nextDirection.nextRoad
        binding.txtRoadAdditionalInfo.text = data.nextDirection.nextRoadAdditionalInfo
        binding.txtDistance.text = data.nextDirection.distance
        binding.txtEta.text = "${data.eta.ete} - ${data.eta.eta} - ${data.eta.distance}"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mUiBinding = FragmentHomeBinding.inflate(inflater, container, false)

        mUiBinding!!.btnDisconnect.setOnClickListener {
            Timber.i("Disconnect request");
            ServiceManager.stopBroadcastService(activity as MainActivity)
        }
        mUiBinding!!.btnExportExecutionLog.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.execution_log_title)
                .setMessage(R.string.execution_log_privacy_warning)
                .setPositiveButton(R.string.export_log) { _, _ ->
                    exportLogRequest.launch("catdrive-execution.log")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val viewModel = ViewModelProvider(requireActivity())[ActivityViewModel::class.java]
        mUiBinding!!.btnNotificationDiagnostic.setOnClickListener {
            showNotificationDiagnostic(viewModel.notificationDiagnostic.value.orEmpty())
        }
        viewModel.navigationData.observe(viewLifecycleOwner) {
            displayNavigationData(it)
        }
        viewModel.notificationDiagnostic.observe(viewLifecycleOwner) {
            mDiagnosticTextView?.text = it
        }
        viewModel.speed.observe(viewLifecycleOwner) {
            binding.txtSpeed.text = "$it km/h"
        }

        return binding.root
    }

    private fun showNotificationDiagnostic(report: String) {
        val textView = TextView(requireContext()).apply {
            text = report
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        mDiagnosticTextView = textView

        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.notification_diagnostic_title)
            .setMessage(R.string.notification_diagnostic_privacy_warning)
            .setView(scrollView)
            .setPositiveButton(R.string.copy_diagnostic) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        getString(R.string.notification_diagnostic_title),
                        textView.text
                    )
                )
                Toast.makeText(requireContext(), R.string.diagnostic_copied, Toast.LENGTH_SHORT)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (mDiagnosticTextView === textView) mDiagnosticTextView = null
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mDiagnosticTextView = null
        mUiBinding = null
    }
}