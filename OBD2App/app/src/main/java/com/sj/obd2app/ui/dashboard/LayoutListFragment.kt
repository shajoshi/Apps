package com.sj.obd2app.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.DashboardLayout
import java.io.File

class LayoutListFragment : Fragment() {

    private lateinit var repo: LayoutRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LayoutsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_layout_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = LayoutRepository(requireContext())
        
        recycler = view.findViewById(R.id.recycler_layouts)
        recycler.layoutManager = LinearLayoutManager(context)
        
        val btnCreateNew: Button = view.findViewById(R.id.btn_create_new)
        btnCreateNew.setOnClickListener {
            // Navigate without args to create a new layout
            findNavController().navigate(R.id.action_layoutList_to_editor)
        }
        
        loadLayouts()
    }

    private fun loadLayouts() {
        val layouts = repo.getSavedLayouts()
        adapter = LayoutsAdapter(layouts)
        recycler.adapter = adapter
    }

    private inner class LayoutsAdapter(private val layouts: List<DashboardLayout>) :
        RecyclerView.Adapter<LayoutsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(android.R.id.text1)
            
            init {
                // Layout defined inline or using standard android layouts
                // for simplicity in this example
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val layout = layouts[position]
            holder.txtName.text = layout.name
            holder.txtName.setTextColor(0xFFFFFFFF.toInt())
            
            holder.itemView.setOnClickListener {
                // Pass layout name as argument to load it in the editor
                val bundle = Bundle().apply {
                    putString("layout_name", layout.name)
                }
                findNavController().navigate(R.id.action_layoutList_to_editor, bundle)
            }
            
            holder.itemView.setOnLongClickListener {
                // Export intent
                shareLayout(layout.name)
                true
            }
        }

        override fun getItemCount() = layouts.size
    }

    private fun shareLayout(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val file = File(requireContext().filesDir, "layouts/$safeName.json")
        
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
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
}
