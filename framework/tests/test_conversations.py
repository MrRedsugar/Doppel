import json
import pytest
from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import NotFound, Conflict

@pytest.fixture
def runtime(tmp_path):
    return DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))

def completed(runtime):
    device = runtime.register_device('alice', 'phone-a', 'Phone')
    run = runtime.create_run('alice', device.id, '读取测试账本', 'full')
    runtime.set_status(run.id, 'completed', '午饭16.26元，饮料3元，合计19.26元')
    return device, run

def test_follow_up_links_previous_results_but_keeps_its_own_permissions(runtime):
    device, parent = completed(runtime)
    child = runtime.create_run('alice', device.id, '和另一个账本对比', 'ask', parent_run_id=parent.id)
    assert child.parent_run_id == parent.id
    assert child.mode == 'ask'
    history = runtime.conversation('alice', child.id)
    assert '19.26' in json.dumps(history, ensure_ascii=False)
    assert all('mode' not in row and 'pending_request' not in row for row in history)
    assert history[0]['title'] == '读取测试账本'
    assert history[0]['conversation_id'] == child.conversation_id
    assert history[0]['messages'][0]['role'] == 'user'
    assert history[-1]['messages'][0]['text'] == '和另一个账本对比'

def test_parent_is_owned_by_same_user_and_device(runtime):
    device, parent = completed(runtime)
    other = runtime.register_device('bob', 'phone-b', 'Other')
    with pytest.raises(NotFound):
        runtime.create_run('bob', other.id, '继续', 'full', parent_run_id=parent.id)
    second = runtime.register_device('alice', 'phone-c', 'Second')
    with pytest.raises(Conflict):
        runtime.create_run('alice', second.id, '继续', 'full', parent_run_id=parent.id)

def test_new_conversation_and_deleted_source_do_not_leak_history(runtime):
    device, parent = completed(runtime)
    child = runtime.create_run('alice', device.id, '继续', 'ask', parent_run_id=parent.id)
    with runtime.store.transaction() as db:
        db.execute('DELETE FROM events WHERE run_id=?', (parent.id,))
        db.execute('DELETE FROM runs WHERE id=?', (parent.id,))
    assert len(runtime.conversation('alice', child.id)) == 1
    runtime.cancel_run('alice', child.id)
    separate = runtime.create_run('alice', device.id, '新对话', 'ask')
    assert len(runtime.conversation('alice', separate.id)) == 1

def test_background_run_is_task_only_and_does_not_create_conversation(runtime):
    device = runtime.register_device('alice', 'background-phone', 'Phone')
    run = runtime.create_run('alice', device.id, '执行自动触发任务', 'assist',
                             conversation_enabled=False, source='trigger')
    assert run.conversation_id is None
    assert run.conversation_messages == []
    assert runtime.conversation('alice', run.id) == []
    runtime.set_status(run.id, 'completed', '自动任务已完成')
    assert runtime.conversation('alice', run.id) == []

def test_background_run_cannot_be_attached_to_conversation(runtime):
    device, parent = completed(runtime)
    with pytest.raises(Conflict):
        runtime.create_run('alice', device.id, '自动任务', 'assist', parent_run_id=parent.id,
                           conversation_enabled=False)

def test_conversation_cannot_attach_to_background_task(runtime):
    device = runtime.register_device('alice', 'phone-background-parent', 'Phone')
    background = runtime.create_run('alice', device.id, '后台任务', 'assist', conversation_enabled=False,
                                    source='schedule')
    runtime.set_status(background.id, 'completed')
    with pytest.raises(Conflict):
        runtime.create_run('alice', device.id, '后续对话', 'assist', parent_run_id=background.id)
