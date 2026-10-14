package com.find.my.phone.gps.location.tracker.ui.onboarding

import android.animation.Animator
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.find.my.phone.gps.location.tracker.databinding.TileOnboardingBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion

class IntroAdapter(
    private val pages: List<IntroPage>,
) : RecyclerView.Adapter<IntroAdapter.VH>() {

    inner class VH(val binding: TileOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Looping animators of the current scene; cancelled on recycle. */
        private val anims = mutableListOf<Animator>()

        fun bind(page: IntroPage) {
            DesignMotion.cancelAll(anims)
            val container = binding.artContainer
            container.removeAllViews()
            LayoutInflater.from(container.context).inflate(page.sceneRes, container, true)
            // The self-animating views (ping rings, roads, typing) start on attach; the
            // element loops that need ids (floaty, spin, fade-in, blink, glow) are wired here.
            anims += IntroAnimations.attach(container)
            binding.tvTitle.setText(page.titleRes)
            binding.tvDesc.setText(page.descRes)
        }

        fun recycle() = DesignMotion.cancelAll(anims)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TileOnboardingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
