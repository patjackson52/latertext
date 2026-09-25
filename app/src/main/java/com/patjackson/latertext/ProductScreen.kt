package com.patjackson.latertext

import com.patjackson.latertext.product.ProductIds

/** Static product vocabulary; these tags never contain user data. */
internal val AppScreen.productComponentId: String
    get() = when (this) {
        AppScreen.UPCOMING -> ProductIds.component_latertext_upcoming_content
        AppScreen.HISTORY -> ProductIds.component_latertext_history_content
        AppScreen.SETTINGS -> ProductIds.component_latertext_settings_content
        AppScreen.COMPOSER -> ProductIds.component_latertext_composer_content
        AppScreen.SCHEDULE_EDITOR -> ProductIds.component_latertext_schedule_editor_content
        AppScreen.DETAIL -> ProductIds.component_latertext_detail_content
    }
