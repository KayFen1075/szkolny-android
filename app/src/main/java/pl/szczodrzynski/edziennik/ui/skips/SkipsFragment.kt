/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.ui.skips

import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.enums.FeatureType
import pl.szczodrzynski.edziennik.databinding.BasePagerFragmentBinding
import pl.szczodrzynski.edziennik.ui.base.fragment.PagerFragment

class SkipsFragment : PagerFragment<BasePagerFragmentBinding, MainActivity>(
    inflater = BasePagerFragmentBinding::inflate,
) {

    override var savedPageSelection
        get() = app.profile.config.skips.skipsPageSelection
        set(value) {
            app.profile.config.skips.skipsPageSelection = value
        }

    override fun getSyncParams() = FeatureType.ATTENDANCE to null

    override fun getTabLayout() = b.tabLayout
    override fun getViewPager() = b.viewPager
    override suspend fun onCreatePages() = listOf(
        SkipsOverviewFragment() to getString(R.string.skips_tab_overview),
        SkipsPlannedFragment() to getString(R.string.skips_tab_planned),
        SkipsSettingsFragment() to getString(R.string.skips_tab_settings),
    )
}
