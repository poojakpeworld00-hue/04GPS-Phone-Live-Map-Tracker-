package com.find.phone.gps.live.location.tracker.screens.welcome

import android.animation.Animator
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.databinding.CellOnboardingBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement

class OpeningAdapter(
    private val pages: List<OpeningFolio>,
) : RecyclerView.Adapter<OpeningAdapter.VH>() {

    inner class VH(val binding: CellOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Looping animators of the current scene; cancelled on recycle. */
        private val anims = mutableListOf<Animator>()

        fun bind(page: OpeningFolio) {
            CraftMovement.abortAll(anims)
            val container = binding.lvArtContainer
            container.removeAllViews()
            LayoutInflater.from(container.context).inflate(page.sceneRes, container, true)
            // The self-animating views (ping rings, roads, typing) start on attach; the
            // element loops that need ids (floaty, spin, fade-in, blink, glow) are wired here.
            anims += OpeningTweens.attach(container)
            binding.txtTitle.setText(page.titleRes)
            binding.txtDesc.setText(page.descRes)
        }

        fun recycle() = CraftMovement.abortAll(anims)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellOnboardingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
