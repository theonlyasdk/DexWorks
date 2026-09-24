package com.asdk.tools.dexworks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.asdk.tools.dexworks.databinding.FragmentToolsBinding
import com.google.android.material.snackbar.Snackbar

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val showNotImplemented = View.OnClickListener {
            Snackbar.make(binding.root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
        }

        binding.toolDecompiler.setOnClickListener(showNotImplemented)
        binding.toolDisassembler.setOnClickListener(showNotImplemented)
        binding.toolExtractor.setOnClickListener(showNotImplemented)
        binding.toolManifest.setOnClickListener(showNotImplemented)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
