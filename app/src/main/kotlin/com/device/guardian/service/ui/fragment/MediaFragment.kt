package com.device.guardian.service.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.device.guardian.service.databinding.FragmentMediaBinding
import com.device.guardian.service.ui.adapter.MediaAdapter
import com.device.guardian.service.ui.adapter.MediaItem
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

class MediaFragment : Fragment() {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!
    
    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: MediaAdapter
    
    private val parentId by lazy {
        requireActivity().intent.getStringExtra("parent_id") ?: "default_parent"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        loadMediaInventory()
        
        binding.swipeRefresh.setOnRefreshListener {
            loadMediaInventory()
        }
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onRequestClick = { fileId -> requestFile(fileId) },
            onViewClick = { url -> viewFile(url) }
        )
        binding.rvMedia.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMedia.adapter = adapter
    }

    private fun loadMediaInventory() {
        binding.progressBar.visibility = View.VISIBLE
        
        db.collection("monitors")
            .document(parentId)
            .collection("media_inventory")
            .orderBy("lastModified", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                if (e != null) {
                    Toast.makeText(context, "Error loading media", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                
                if (snapshot != null && !snapshot.isEmpty) {
                    binding.tvEmpty.visibility = View.GONE
                    val items = snapshot.documents.mapNotNull { doc ->
                        try {
                            MediaItem(
                                fileId = doc.id,
                                fileName = doc.getString("fileName") ?: "Unknown",
                                sizeBytes = doc.getLong("sizeBytes") ?: 0L,
                                lastModified = doc.getLong("lastModified") ?: 0L,
                                mimeType = doc.getString("mimeType") ?: "application/octet-stream",
                                requestStatus = doc.getString("requestStatus") ?: "AVAILABLE",
                                downloadUrl = doc.getString("downloadUrl")
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    adapter.submitList(items)
                } else {
                    binding.tvEmpty.visibility = View.VISIBLE
                    adapter.submitList(emptyList())
                }
            }
    }

    private fun requestFile(fileId: String) {
        db.collection("monitors")
            .document(parentId)
            .collection("media_inventory")
            .document(fileId)
            .set(mapOf("requestStatus" to "REQUESTED"), SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(context, "Request sent to device", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to send request", Toast.LENGTH_SHORT).show()
            }
    }

    private fun viewFile(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app to view this file", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
