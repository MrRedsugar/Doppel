import pytest
from pydantic import ValidationError

from doppel.models import Command


@pytest.mark.parametrize("desired", [True, False, None])
def test_tap_accepts_an_explicit_desired_state_or_legacy_absence(desired):
    command = Command(id="c", run_id="r", kind="tap", target="toggle", screen_id="s",
                      desired_checked=desired)
    assert command.desired_checked is desired


@pytest.mark.parametrize("desired", [0, 1, "true", "false", [], {}])
def test_desired_checked_rejects_coercion_from_non_booleans(desired):
    with pytest.raises(ValidationError):
        Command(id="c", run_id="r", kind="tap", target="toggle", screen_id="s",
                desired_checked=desired)


@pytest.mark.parametrize("kind,fields", [
    ("observe", {}), ("long_press", {"target": "toggle", "screen_id": "s"}),
    ("type", {"target": "input", "screen_id": "s", "text": "value"}),
    ("scroll", {"direction": "down"}), ("back", {}), ("home", {}),
    ("launch", {"package_name": "fixture"}), ("wait", {}), ("screenshot", {}),
    ("open_document", {"uri": "content://fixture/file"}),
    ("login_phone", {"target": "input", "screen_id": "s", "package_name": "fixture"}),
    ("login_code", {"target": "input", "screen_id": "s", "package_name": "fixture"}),
])
@pytest.mark.parametrize("desired", [True, False])
def test_other_actions_cannot_carry_a_desired_checked_state(kind, fields, desired):
    with pytest.raises(ValidationError, match="desired_checked is only supported for tap"):
        Command(id="c", run_id="r", kind=kind, desired_checked=desired, **fields)
