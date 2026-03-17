package com.sj.obd2app.ui.dashboard

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sj.obd2app.R
import com.sj.obd2app.ui.attachNavOverflow
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.DashboardLayout
import com.sj.obd2app.ui.dashboard.model.DashboardOrientation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LayoutListFragment : Fragment() {

    private lateinit var repo: LayoutRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LayoutsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_layout_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = LayoutRepository(requireContext())

        view.findViewById<android.widget.TextView>(R.id.txt_top_bar_title).text = "Saved Dashboards"
        attachNavOverflow(view.findViewById(R.id.btn_top_overflow))

        recycler = view.findViewById(R.id.recycler_layouts)
        recycler.layoutManager = LinearLayoutManager(context)

        view.findViewById<Button>(R.id.btn_create_new).setOnClickListener {
            showCreateNewDialog()
        }

        loadLayouts()
    }

    override fun onResume() {
        super.onResume()
        loadLayouts()
    }

    private fun loadLayouts() {
        val layouts = repo.getSavedLayouts()
        val defaultName = repo.getDefaultLayoutName()
        adapter = LayoutsAdapter(layouts.toMutableList(), defaultName)
        recycler.adapter = adapter
    }

    private fun showCreateNewDialog() {
        val ctx = requireContext()
        val inputLayout = TextInputLayout(ctx).apply {
            hint = "Dashboard name"
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(ctx)
        inputLayout.addView(input)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("New Dashboard")
            .setView(inputLayout)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isBlank()) {
                    Toast.makeText(ctx, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    val bundle = Bundle().apply {
                        putString("layout_name", name)
                        putString("mode", "edit")
                        putBoolean("is_new", true)
                    }
                    findNavController().navigate(R.id.action_layoutList_to_editor, bundle)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentOrientation(): DashboardOrientation {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            DashboardOrientation.LANDSCAPE else DashboardOrientation.PORTRAIT
    }

    private fun openDashboard(name: String, mode: String) {
        val layout = repo.getSavedLayouts().find { it.name == name }
        if (layout != null && layout.orientation != currentOrientation()) {
            val required = layout.orientation.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            Toast.makeText(
                requireContext(),
                "\"$name\" requires $required mode. Please rotate your device.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val bundle = Bundle().apply {
            putString("layout_name", name)
            putString("mode", mode)
        }
        findNavController().navigate(R.id.action_layoutList_to_editor, bundle)
    }

    private fun shareLayout(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val file = File(requireContext().filesDir, "layouts/$safeName.json")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, "Share Dashboard Layout"))
        } else {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class LayoutsAdapter(
        private val layouts: MutableList<DashboardLayout>,
        private var defaultName: String?
    ) : RecyclerView.Adapter<LayoutsAdapter.VH>() {

        private var expandedPosition = -1
        private var lastClickTime = 0L
        private var lastClickPosition = -1

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView     = view.findViewById(R.id.txt_layout_name)
            val txtMeta: TextView     = view.findViewById(R.id.txt_layout_meta)
            val actionRow: LinearLayout = view.findViewById(R.id.action_row)
            val btnOpen: Button       = view.findViewById(R.id.btn_open)
            val btnEdit: Button       = view.findViewById(R.id.btn_edit)
            val btnShare: Button      = view.findViewById(R.id.btn_share)
            val btnDelete: Button     = view.findViewById(R.id.btn_delete)
            val btnStar: ImageButton  = view.findViewById(R.id.btn_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dashboard_layout, parent, false))

        override fun getItemCount() = layouts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val layout = layouts[position]
            val isDefault = layout.name == defaultName

            holder.txtName.text = layout.name

            // Meta: widget count + last modified date
            val safeName = layout.name.replace(Regex("[^A-Za-z0-9 _-]"), "")
            val file = File(requireContext().filesDir, "layouts/$safeName.json")
            val dateStr = if (file.exists()) {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))
            } else ""
            val widgetCount = layout.widgets.size
            val orientLabel = layout.orientation.name.lowercase().replaceFirstChar { it.uppercase() }
            holder.txtMeta.text = "$widgetCount widget${if (widgetCount != 1) "s" else ""}  ·  $orientLabel  ·  $dateStr"

            // Star
            holder.btnStar.setImageResource(
                if (isDefault) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            holder.btnStar.setColorFilter(
                if (isDefault) 0xFFFFC107.toInt() else 0xFF888888.toInt()
            )
            holder.btnStar.setOnClickListener {
                if (isDefault) {
                    repo.clearDefaultLayout()
                    defaultName = null
                } else {
                    repo.setDefaultLayoutName(layout.name)
                    defaultName = layout.name
                }
                notifyDataSetChanged()
            }

            // Action row visibility
            holder.actionRow.visibility =
                if (expandedPosition == position) View.VISIBLE else View.GONE

            // Single tap: expand/collapse action row; double tap: open in view mode
            holder.itemView.setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                val pos = holder.bindingAdapterPosition
                if (pos == lastClickPosition && now - lastClickTime < 400L) {
                    // Double-tap → view mode
                    lastClickTime = 0L
                    lastClickPosition = -1
                    openDashboard(layout.name, "view")
                } else {
                    lastClickTime = now
                    lastClickPosition = pos
                    val prev = expandedPosition
                    expandedPosition = if (expandedPosition == pos) -1 else pos
                    if (prev != -1) notifyItemChanged(prev)
                    notifyItemChanged(pos)
                }
            }

            holder.btnOpen.setOnClickListener  { openDashboard(layout.name, "view") }
            holder.btnEdit.setOnClickListener  { openDashboard(layout.name, "edit") }
            holder.btnShare.setOnClickListener { shareLayout(layout.name) }
            holder.btnDelete.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete \"${layout.name}\"?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        repo.deleteLayout(layout.name)
                        val pos = holder.bindingAdapterPosition
                        layouts.removeAt(pos)
                        if (expandedPosition == pos) expandedPosition = -1
                        else if (expandedPosition > pos) expandedPosition--
                        notifyItemRemoved(pos)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
