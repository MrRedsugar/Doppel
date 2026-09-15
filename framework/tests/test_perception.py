from doppel.models import Node, Observation
from doppel.perception import compact_observation


def test_compaction_hides_password_and_preserves_unnamed_target():
    observation = Observation(screen_id="s1", package_name="test.app", width=100, height=200, nodes=[
        Node(id="p1", text="actual-secret", password=True, bounds=[0, 0, 10, 10]),
        Node(id="n2", role="button", clickable=True, bounds=[20, 20, 50, 60]),
        Node(id="n3", text="确定", clickable=True, bounds=[50, 20, 80, 60]),
    ])
    result = compact_observation(observation)
    assert "actual-secret" not in result
    assert "n2" in result and "n3" in result and "确定" in result
    assert len(result) < 600


def test_wps_label_is_attached_to_the_actual_tappable_container_without_parsing_ids():
    observation = Observation(screen_id="wps", package_name="cn.wps.moffice_eng", width=1440, height=3200, nodes=[
        Node(id="opaque-container", role="button", clickable=True, bounds=[308, 322, 616, 462]),
        Node(id="unrelated-opaque-id", role="android.widget.Button", text="Summary", bounds=[308, 322, 616, 462]),
    ])
    before = observation.model_dump()
    lines = compact_observation(observation).splitlines()
    assert "opaque-container Summary (button,tap)" in lines
    assert "unrelated-opaque-id Summary (Button,read-only)" in lines
    assert observation.model_dump() == before


def test_container_does_not_borrow_ambiguous_interactive_or_password_labels():
    for labels in (
        [Node(id="a", text="First", bounds=[20, 20, 40, 40]), Node(id="b", text="Second", bounds=[50, 20, 80, 40])],
        [Node(id="a", text="Nested action", clickable=True, bounds=[20, 20, 40, 40])],
        [Node(id="a", text="Secret", password=True, bounds=[20, 20, 40, 40])],
    ):
        observation = Observation(screen_id="s", package_name="fixture", width=1000, height=2000, nodes=[
            Node(id="container", clickable=True, bounds=[10, 10, 100, 100]), *labels,
        ])
        line = next(line for line in compact_observation(observation).splitlines() if line.startswith("container "))
        assert "unlabeled at" in line
        assert "Secret" not in compact_observation(observation)


def test_disabled_control_never_advertises_tap_and_large_overlay_keeps_unnamed():
    observation = Observation(screen_id="s", package_name="fixture", width=1000, height=2000, nodes=[
        Node(id="overlay", clickable=True, bounds=[0, 0, 1000, 2000]),
        Node(id="disabled", text="Unavailable", clickable=True, enabled=False, bounds=[10, 10, 100, 100]),
        Node(id="label", text="Background", bounds=[200, 200, 300, 300]),
    ])
    lines = compact_observation(observation).splitlines()
    assert "unlabeled at" in next(line for line in lines if line.startswith("overlay "))
    assert "tap" not in next(line for line in lines if line.startswith("disabled "))


def test_same_label_exposes_checked_state_without_inferring_it_from_a_counter():
    screens = []
    for checked in (False, True):
        observation = Observation(screen_id="s", package_name="fixture", width=100, height=200, nodes=[
            Node(id="toggle", text="Like, 9812 likes", clickable=True, checkable=True,
                 checked=checked, selected=False, bounds=[0, 0, 80, 40]),
        ])
        screens.append(compact_observation(observation))
    assert "checked=false" in screens[0]
    assert "checked=true" in screens[1]
    assert screens[0] != screens[1]


def test_legacy_nodes_keep_unknown_state_and_do_not_claim_unchecked():
    legacy = Node(id="old", text="Like", clickable=True, bounds=[0, 0, 80, 40])
    assert legacy.checkable is None and legacy.checked is None and legacy.selected is None
    known_toggle = Node(id="toggle", text="Like", clickable=True, checkable=True,
                        bounds=[0, 40, 80, 80])
    observation = Observation(screen_id="s", package_name="fixture", width=100, height=200,
                              nodes=[legacy, known_toggle])
    lines = compact_observation(observation).splitlines()
    assert "checked=" not in next(line for line in lines if line.startswith("old "))
    assert "checked=unknown" in next(line for line in lines if line.startswith("toggle "))


def test_selected_and_state_only_nodes_remain_visible_with_bounded_single_line_state():
    observation = Observation(screen_id="s", package_name="fixture", width=100, height=200, nodes=[
        Node(id="selected", text="Tab", selected=True, bounds=[0, 0, 80, 30]),
        Node(id="idle", text="Tab", selected=False, bounds=[0, 30, 80, 60]),
        Node(id="state", state_description="  Playing\n at\t50%  ", bounds=[0, 60, 80, 90]),
        Node(id="long", state_description="x" * 4096, bounds=[0, 90, 80, 120]),
    ])
    lines = compact_observation(observation).splitlines()
    assert "selected=true" in next(line for line in lines if line.startswith("selected "))
    assert "selected=" not in next(line for line in lines if line.startswith("idle "))
    assert "state=Playing at 50%" in next(line for line in lines if line.startswith("state "))
    assert "x" * 300 in next(line for line in lines if line.startswith("long "))
    assert "x" * 301 not in "\n".join(lines)
    assert len(lines) == 5


def test_password_state_description_is_redacted_before_persistence_and_compaction():
    secret = Node(id="private", text="secret", description="secret", state_description="secret",
                  password=True, bounds=[0, 0, 80, 30])
    assert "secret" not in secret.model_dump_json()
    observation = Observation(screen_id="s", package_name="fixture", width=100, height=200, nodes=[secret])
    assert "private" not in compact_observation(observation)
