"""Generated Product Definition IDs. Do not edit by hand.

Regenerate with ``python scripts/generate_product_ids.py --write``.
"""

from dataclasses import dataclass


PRODUCT_DEFINITION_DIGEST = "sha256:f1db52836f179b6928829c76fcfcfd57bcb72a1f620051adcd744c52d7f762b2"


@dataclass(frozen=True, slots=True)
class ProductIds:
    surface_latertext_upcoming: str = "surface:latertext.upcoming"
    component_latertext_upcoming_content: str = "component:latertext.upcoming_content"
    surface_latertext_history: str = "surface:latertext.history"
    component_latertext_history_content: str = "component:latertext.history_content"
    surface_latertext_settings: str = "surface:latertext.settings"
    component_latertext_settings_content: str = "component:latertext.settings_content"
    surface_latertext_composer: str = "surface:latertext.composer"
    component_latertext_composer_content: str = "component:latertext.composer_content"
    surface_latertext_schedule_editor: str = "surface:latertext.schedule_editor"
    component_latertext_schedule_editor_content: str = "component:latertext.schedule_editor_content"
    surface_latertext_detail: str = "surface:latertext.detail"
    component_latertext_detail_content: str = "component:latertext.detail_content"
    action_latertext_compose: str = "action:latertext.compose"
    action_latertext_save_schedule: str = "action:latertext.save_schedule"
    action_latertext_send_now: str = "action:latertext.send_now"
    action_latertext_pause_schedule: str = "action:latertext.pause_schedule"
    action_latertext_delete_schedule: str = "action:latertext.delete_schedule"
    action_latertext_assisted_handoff: str = "action:latertext.assisted_handoff"
    action_latertext_pause_all: str = "action:latertext.pause_all"
    state_latertext_upcoming_empty: str = "state:latertext.upcoming_empty"
    state_latertext_composer_blank: str = "state:latertext.composer_blank"
    state_latertext_schedule_editing: str = "state:latertext.schedule_editing"
    state_latertext_sent_to_carrier: str = "state:latertext.sent_to_carrier"
    state_latertext_delivered: str = "state:latertext.delivered"
    state_latertext_action_required: str = "state:latertext.action_required"
    state_latertext_failed: str = "state:latertext.failed"
    behavior_latertext_durable_execution: str = "behavior:latertext.durable_execution"
    behavior_latertext_recovery: str = "behavior:latertext.recovery"


PRODUCT_IDS = ProductIds()
PRODUCT_ID_FIELDS = {
    "surface_latertext_upcoming": PRODUCT_IDS.surface_latertext_upcoming,
    "component_latertext_upcoming_content": PRODUCT_IDS.component_latertext_upcoming_content,
    "surface_latertext_history": PRODUCT_IDS.surface_latertext_history,
    "component_latertext_history_content": PRODUCT_IDS.component_latertext_history_content,
    "surface_latertext_settings": PRODUCT_IDS.surface_latertext_settings,
    "component_latertext_settings_content": PRODUCT_IDS.component_latertext_settings_content,
    "surface_latertext_composer": PRODUCT_IDS.surface_latertext_composer,
    "component_latertext_composer_content": PRODUCT_IDS.component_latertext_composer_content,
    "surface_latertext_schedule_editor": PRODUCT_IDS.surface_latertext_schedule_editor,
    "component_latertext_schedule_editor_content": PRODUCT_IDS.component_latertext_schedule_editor_content,
    "surface_latertext_detail": PRODUCT_IDS.surface_latertext_detail,
    "component_latertext_detail_content": PRODUCT_IDS.component_latertext_detail_content,
    "action_latertext_compose": PRODUCT_IDS.action_latertext_compose,
    "action_latertext_save_schedule": PRODUCT_IDS.action_latertext_save_schedule,
    "action_latertext_send_now": PRODUCT_IDS.action_latertext_send_now,
    "action_latertext_pause_schedule": PRODUCT_IDS.action_latertext_pause_schedule,
    "action_latertext_delete_schedule": PRODUCT_IDS.action_latertext_delete_schedule,
    "action_latertext_assisted_handoff": PRODUCT_IDS.action_latertext_assisted_handoff,
    "action_latertext_pause_all": PRODUCT_IDS.action_latertext_pause_all,
    "state_latertext_upcoming_empty": PRODUCT_IDS.state_latertext_upcoming_empty,
    "state_latertext_composer_blank": PRODUCT_IDS.state_latertext_composer_blank,
    "state_latertext_schedule_editing": PRODUCT_IDS.state_latertext_schedule_editing,
    "state_latertext_sent_to_carrier": PRODUCT_IDS.state_latertext_sent_to_carrier,
    "state_latertext_delivered": PRODUCT_IDS.state_latertext_delivered,
    "state_latertext_action_required": PRODUCT_IDS.state_latertext_action_required,
    "state_latertext_failed": PRODUCT_IDS.state_latertext_failed,
    "behavior_latertext_durable_execution": PRODUCT_IDS.behavior_latertext_durable_execution,
    "behavior_latertext_recovery": PRODUCT_IDS.behavior_latertext_recovery,
}
PRODUCT_ENTITY_IDS = frozenset((
    PRODUCT_IDS.surface_latertext_upcoming,
    PRODUCT_IDS.component_latertext_upcoming_content,
    PRODUCT_IDS.surface_latertext_history,
    PRODUCT_IDS.component_latertext_history_content,
    PRODUCT_IDS.surface_latertext_settings,
    PRODUCT_IDS.component_latertext_settings_content,
    PRODUCT_IDS.surface_latertext_composer,
    PRODUCT_IDS.component_latertext_composer_content,
    PRODUCT_IDS.surface_latertext_schedule_editor,
    PRODUCT_IDS.component_latertext_schedule_editor_content,
    PRODUCT_IDS.surface_latertext_detail,
    PRODUCT_IDS.component_latertext_detail_content,
    PRODUCT_IDS.action_latertext_compose,
    PRODUCT_IDS.action_latertext_save_schedule,
    PRODUCT_IDS.action_latertext_send_now,
    PRODUCT_IDS.action_latertext_pause_schedule,
    PRODUCT_IDS.action_latertext_delete_schedule,
    PRODUCT_IDS.action_latertext_assisted_handoff,
    PRODUCT_IDS.action_latertext_pause_all,
    PRODUCT_IDS.state_latertext_upcoming_empty,
    PRODUCT_IDS.state_latertext_composer_blank,
    PRODUCT_IDS.state_latertext_schedule_editing,
    PRODUCT_IDS.state_latertext_sent_to_carrier,
    PRODUCT_IDS.state_latertext_delivered,
    PRODUCT_IDS.state_latertext_action_required,
    PRODUCT_IDS.state_latertext_failed,
    PRODUCT_IDS.behavior_latertext_durable_execution,
    PRODUCT_IDS.behavior_latertext_recovery,
))


__all__ = [
    "PRODUCT_DEFINITION_DIGEST",
    "PRODUCT_ENTITY_IDS",
    "PRODUCT_ID_FIELDS",
    "PRODUCT_IDS",
    "ProductIds",
]
