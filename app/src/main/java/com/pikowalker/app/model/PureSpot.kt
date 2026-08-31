package com.pikowalker.app.model

/** One "純點" — a community-curated spot known not to overlap another Pikmin Bloom player's
 *  claimed decor, so seeding a flower/mushroom there doesn't get wasted on a duplicate. Sourced
 *  from a public database (see PureSpotRepository), not user-editable within PikoWalker. */
data class PureSpot(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: String,
    val icon: String,
    val city: String,
    val district: String
)

/** 純點 tab's search box + 縣市/行政區/種類 selections — held in WalkViewModel (see
 *  WalkViewModel.pureSpotFilters) rather than local Composable state, because switching bottom
 *  nav tabs disposes and recreates PureSpotScreen's whole composition; plain `remember` state
 *  would silently reset to blank on every visit instead of picking up where the user left off. */
data class PureSpotFilters(
    val searchText: String = "",
    val city: String? = null,
    val district: String? = null,
    val type: String? = null
)
