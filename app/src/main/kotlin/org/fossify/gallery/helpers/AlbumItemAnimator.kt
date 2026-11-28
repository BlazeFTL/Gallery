package org.fossify.gallery.helpers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Item animator for the album grid. New albums fade and scale in one after another, the rest of the grid glides
 * to its new place, removed albums shrink away. Changed albums are rebound in place, so no ghost copies show up.
 */
class AlbumItemAnimator(density: Float) : SimpleItemAnimator() {
    private class MoveInfo(
        val holder: RecyclerView.ViewHolder,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int
    )

    private val pendingRemovals = ArrayList<RecyclerView.ViewHolder>()
    private val pendingAdditions = ArrayList<RecyclerView.ViewHolder>()
    private val pendingMoves = ArrayList<MoveInfo>()
    private val removeAnimations = ArrayList<RecyclerView.ViewHolder>()
    private val addAnimations = ArrayList<RecyclerView.ViewHolder>()
    private val moveAnimations = ArrayList<RecyclerView.ViewHolder>()

    private val enterOffset = 20f * density
    private val enterInterpolator = DecelerateInterpolator(2f)
    private val moveInterpolator = DecelerateInterpolator(1.6f)
    private val exitInterpolator = AccelerateInterpolator(1.2f)
    private val defaultInterpolator = ValueAnimator().interpolator

    init {
        supportsChangeAnimations = false
        addDuration = 360L
        moveDuration = 300L
        removeDuration = 180L
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        pendingRemovals.add(holder)
        return true
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        holder.itemView.apply {
            alpha = 0f
            scaleX = ENTER_SCALE
            scaleY = ENTER_SCALE
            translationY = enterOffset
        }
        pendingAdditions.add(holder)
        return true
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val view = holder.itemView
        val startX = fromX + view.translationX.toInt()
        val startY = fromY + view.translationY.toInt()
        endAnimation(holder)
        val deltaX = toX - startX
        val deltaY = toY - startY
        if (deltaX == 0 && deltaY == 0) {
            dispatchMoveFinished(holder)
            return false
        }

        view.translationX = -deltaX.toFloat()
        view.translationY = -deltaY.toFloat()
        pendingMoves.add(MoveInfo(holder, startX, startY, toX, toY))
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder?,
        newHolder: RecyclerView.ViewHolder?,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        if (oldHolder != null && oldHolder === newHolder) {
            return animateMove(oldHolder, fromX, fromY, toX, toY)
        }

        if (oldHolder != null) {
            endAnimation(oldHolder)
            dispatchChangeFinished(oldHolder, true)
        }
        if (newHolder != null) {
            endAnimation(newHolder)
            dispatchChangeFinished(newHolder, false)
        }
        return false
    }

    override fun runPendingAnimations() {
        val hasRemovals = pendingRemovals.isNotEmpty()
        val hasMoves = pendingMoves.isNotEmpty()
        val hasAdditions = pendingAdditions.isNotEmpty()
        if (!hasRemovals && !hasMoves && !hasAdditions) {
            return
        }

        val removals = ArrayList(pendingRemovals)
        pendingRemovals.clear()
        removals.forEach { animateRemoveImpl(it) }

        val moveDelay = if (hasRemovals) removeDuration else 0L
        val moves = ArrayList(pendingMoves)
        pendingMoves.clear()
        moves.forEach { animateMoveImpl(it, moveDelay) }

        if (hasAdditions) {
            val additions = ArrayList(pendingAdditions)
            pendingAdditions.clear()
            additions.sortBy { it.layoutPosition }
            val baseDelay = moveDelay + if (hasMoves) moveDuration * 2 / 3 else 0L
            additions.forEachIndexed { index, holder ->
                animateAddImpl(holder, baseDelay + minOf(index, MAX_STAGGERED) * STAGGER_MS)
            }
        }
    }

    private fun animateRemoveImpl(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        val animation = view.animate()
        removeAnimations.add(holder)
        dispatchRemoveStarting(holder)
        animation
            .setDuration(removeDuration)
            .setStartDelay(0L)
            .setInterpolator(exitInterpolator)
            .alpha(0f)
            .scaleX(ENTER_SCALE)
            .scaleY(ENTER_SCALE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    finishAnimation(animation)
                    resetView(view)
                    removeAnimations.remove(holder)
                    dispatchRemoveFinished(holder)
                    dispatchFinishedWhenDone()
                }
            }).start()
    }

    private fun animateMoveImpl(info: MoveInfo, delay: Long) {
        val holder = info.holder
        val view = holder.itemView
        val deltaX = info.toX - info.fromX
        val deltaY = info.toY - info.fromY
        val animation = view.animate()
        moveAnimations.add(holder)
        dispatchMoveStarting(holder)
        if (deltaX != 0) {
            animation.translationX(0f)
        }
        if (deltaY != 0) {
            animation.translationY(0f)
        }
        animation
            .setDuration(moveDuration)
            .setStartDelay(delay)
            .setInterpolator(moveInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animator: Animator) {
                    view.translationX = 0f
                    view.translationY = 0f
                }

                override fun onAnimationEnd(animator: Animator) {
                    finishAnimation(animation)
                    moveAnimations.remove(holder)
                    dispatchMoveFinished(holder)
                    dispatchFinishedWhenDone()
                }
            }).start()
    }

    private fun animateAddImpl(holder: RecyclerView.ViewHolder, delay: Long) {
        val view = holder.itemView
        val animation = view.animate()
        addAnimations.add(holder)
        dispatchAddStarting(holder)
        animation
            .setDuration(addDuration)
            .setStartDelay(delay)
            .setInterpolator(enterInterpolator)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animator: Animator) {
                    resetView(view)
                }

                override fun onAnimationEnd(animator: Animator) {
                    finishAnimation(animation)
                    addAnimations.remove(holder)
                    dispatchAddFinished(holder)
                    dispatchFinishedWhenDone()
                }
            }).start()
    }

    private fun finishAnimation(animation: android.view.ViewPropertyAnimator) {
        animation.setListener(null)
        animation.setStartDelay(0L)
        animation.setInterpolator(defaultInterpolator)
    }

    private fun resetView(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val view = item.itemView
        view.animate().cancel()

        for (i in pendingMoves.indices.reversed()) {
            if (pendingMoves[i].holder === item) {
                view.translationX = 0f
                view.translationY = 0f
                dispatchMoveFinished(item)
                pendingMoves.removeAt(i)
            }
        }
        if (pendingRemovals.remove(item)) {
            resetView(view)
            dispatchRemoveFinished(item)
        }
        if (pendingAdditions.remove(item)) {
            resetView(view)
            dispatchAddFinished(item)
        }
        dispatchFinishedWhenDone()
    }

    override fun endAnimations() {
        for (i in pendingMoves.indices.reversed()) {
            val holder = pendingMoves[i].holder
            holder.itemView.translationX = 0f
            holder.itemView.translationY = 0f
            dispatchMoveFinished(holder)
            pendingMoves.removeAt(i)
        }
        for (i in pendingRemovals.indices.reversed()) {
            val holder = pendingRemovals[i]
            resetView(holder.itemView)
            dispatchRemoveFinished(holder)
            pendingRemovals.removeAt(i)
        }
        for (i in pendingAdditions.indices.reversed()) {
            val holder = pendingAdditions[i]
            resetView(holder.itemView)
            dispatchAddFinished(holder)
            pendingAdditions.removeAt(i)
        }

        if (!isRunning()) {
            return
        }

        (ArrayList(moveAnimations) + ArrayList(removeAnimations) + ArrayList(addAnimations)).forEach {
            it.itemView.animate().cancel()
        }
        dispatchAnimationsFinished()
    }

    override fun isRunning(): Boolean {
        return pendingAdditions.isNotEmpty()
                || pendingRemovals.isNotEmpty()
                || pendingMoves.isNotEmpty()
                || addAnimations.isNotEmpty()
                || removeAnimations.isNotEmpty()
                || moveAnimations.isNotEmpty()
    }

    private fun dispatchFinishedWhenDone() {
        if (!isRunning()) {
            dispatchAnimationsFinished()
        }
    }

    private companion object {
        const val ENTER_SCALE = 0.86f
        const val STAGGER_MS = 32L
        const val MAX_STAGGERED = 12
    }
}
