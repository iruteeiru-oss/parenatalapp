package com.device.guardian.service.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.device.guardian.service.R
import com.device.guardian.service.databinding.FragmentMessagesBinding
import com.device.guardian.service.ui.viewmodel.DashboardViewModel

class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: DashboardViewModel by activityViewModels {
        val parentId = requireActivity().intent.getStringExtra("parent_id") ?: "default_parent"
        DashboardViewModel.Factory(com.device.guardian.service.data.repository.MessageRepository(parentId))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load PlatformsFragment as the root child fragment inside the container
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.containerMessages, PlatformsFragment())
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
