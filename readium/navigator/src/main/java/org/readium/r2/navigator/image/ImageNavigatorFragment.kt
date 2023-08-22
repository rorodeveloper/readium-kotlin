/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.image

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.SimplePresentation
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.databinding.ReadiumNavigatorViewpagerBinding
import org.readium.r2.navigator.extensions.layoutDirectionIsRTL
import org.readium.r2.navigator.input.CompositeInputListener
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyInterceptorView
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pager.R2CbzPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression as PublicationReadingProgression
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positions

/**
 * Navigator for bitmap-based publications, such as CBZ.
 */
@OptIn(ExperimentalReadiumApi::class)
public class ImageNavigatorFragment private constructor(
    override val publication: Publication,
    private val initialLocator: Locator? = null,
    internal val listener: Listener? = null
) : Fragment(), CoroutineScope by MainScope(), VisualNavigator {

    public interface Listener : VisualNavigator.Listener

    init {
        require(!publication.isRestricted) { "The provided publication is restricted. Check that any DRM was properly unlocked using a Content Protection." }
    }

    internal lateinit var positions: List<Locator>
    internal lateinit var resourcePager: R2ViewPager

    internal lateinit var preferences: SharedPreferences

    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(
        initialLocator
            ?: requireNotNull(publication.locatorFromLink(publication.readingOrder.first()))
    )

    internal var currentPagerPosition: Int = 0
    internal var resources: List<String> = emptyList()

    private var _binding: ReadiumNavigatorViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory = createFragmentFactory {
            R2CbzPageFragment(publication) { x, y ->
                inputListener.onTap(
                    this,
                    TapEvent(PointF(x, y))
                )
            }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        currentActivity = requireActivity()
        _binding = ReadiumNavigatorViewpagerBinding.inflate(inflater, container, false)
        val view = binding.root

        preferences = requireContext().getSharedPreferences(
            "org.readium.r2.settings",
            Context.MODE_PRIVATE
        )
        resourcePager = binding.resourcePager
        resourcePager.publicationType = R2ViewPager.PublicationType.CBZ

        positions = runBlocking { publication.positions() }

        resourcePager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                notifyCurrentLocation()
            }
        })

        val resources = publication.readingOrder
            .map { R2PagerAdapter.PageResource.Cbz(it) }
        adapter = R2PagerAdapter(childFragmentManager, resources)

        resourcePager.adapter = adapter

        if (currentPagerPosition == 0) {
            if (requireActivity().layoutDirectionIsRTL()) {
                // The view has RTL layout
                resourcePager.currentItem = resources.size - 1
            } else {
                // The view has LTR layout
                resourcePager.currentItem = currentPagerPosition
            }
        } else {
            resourcePager.currentItem = currentPagerPosition
        }

        if (initialLocator != null) {
            go(initialLocator)
        }

        return KeyInterceptorView(view, this, inputListener)
    }

    override fun onStart() {
        super.onStart()

        // OnPageChangeListener.onPageSelected is not called on the first page of the book, so we
        // trigger the locationDidChange event manually.
        notifyCurrentLocation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @Deprecated(
        "Use goForward instead",
        replaceWith = ReplaceWith("goForward()"),
        level = DeprecationLevel.ERROR
    )
    @Suppress("UNUSED_PARAMETER")
    public fun nextResource(v: View?) {
        goForward()
    }

    @Deprecated(
        "Use goBackward instead",
        replaceWith = ReplaceWith("goBackward()"),
        level = DeprecationLevel.ERROR
    )
    @Suppress("UNUSED_PARAMETER")
    public fun previousResource(v: View?) {
        goBackward()
    }

    private fun notifyCurrentLocation() {
        val locator = positions[resourcePager.currentItem]
        if (locator == _currentLocator.value) {
            return
        }
        _currentLocator.value = locator
    }

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        val resourceIndex = publication.readingOrder.indexOfFirstWithHref(locator.href)
            ?: return false

        listener?.onJumpToLocator(locator)
        currentPagerPosition = resourceIndex
        resourcePager.currentItem = currentPagerPosition

        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated, completion)
    }

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        val current = resourcePager.currentItem
        if (requireActivity().layoutDirectionIsRTL()) {
            // The view has RTL layout
            resourcePager.currentItem = current - 1
        } else {
            // The view has LTR layout
            resourcePager.currentItem = current + 1
        }

        notifyCurrentLocation()
        return current != resourcePager.currentItem
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        val current = resourcePager.currentItem
        if (requireActivity().layoutDirectionIsRTL()) {
            // The view has RTL layout
            resourcePager.currentItem = current + 1
        } else {
            // The view has LTR layout
            resourcePager.currentItem = current - 1
        }

        notifyCurrentLocation()
        return current != resourcePager.currentItem
    }

    // VisualNavigator

    override val publicationView: View
        get() = requireView()

    @Suppress("DEPRECATION")
    @Deprecated(
        "Use `presentation.value.readingProgression` instead",
        replaceWith = ReplaceWith("presentation.value.readingProgression"),
        level = DeprecationLevel.ERROR
    )
    override val readingProgression: PublicationReadingProgression =
        publication.metadata.effectiveReadingProgression

    @ExperimentalReadiumApi
    override val presentation: StateFlow<VisualNavigator.Presentation> =
        MutableStateFlow(
            SimplePresentation(
                readingProgression = when (publication.metadata.readingProgression) {
                    PublicationReadingProgression.RTL -> ReadingProgression.RTL
                    else -> ReadingProgression.LTR
                },
                scroll = false,
                axis = Axis.HORIZONTAL
            )
        ).asStateFlow()

    private val inputListener = CompositeInputListener()

    override fun addInputListener(listener: InputListener) {
        inputListener.add(listener)
    }

    override fun removeInputListener(listener: InputListener) {
        inputListener.remove(listener)
    }

    public companion object {

        /**
         * Factory for [ImageNavigatorFragment].
         *
         * @param publication Bitmap-based publication to render in the navigator.
         * @param initialLocator The first location which should be visible when rendering the
         *        publication. Can be used to restore the last reading location.
         * @param listener Optional listener to implement to observe events, such as user taps.
         */
        public fun createFactory(
            publication: Publication,
            initialLocator: Locator? = null,
            listener: Listener? = null
        ): FragmentFactory =
            createFragmentFactory { ImageNavigatorFragment(publication, initialLocator, listener) }
    }
}
