from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from inference import BusyError, CancelledError, ModelProcess


def isolated_worker(connection,alias,weights,max_pixels):
    """Real spawned process/pipe lifecycle without importing torch or loading any model."""
    connection.send({"ready":True,"load_ms":0})
    try:
        while True:
            job=connection.recv()
            if job["target"]=="slow": time.sleep(2)
            connection.send({"result":{"point":(1,2),"target_seen":job["target"]}})
    finally:
        connection.close()


class Process:
    def __init__(self): self.alive=True
    def is_alive(self): return self.alive
    def terminate(self): self.alive=False
    def join(self,timeout): pass
    def kill(self): self.alive=False


class Connection:
    def __init__(self,polls): self.polls=iter(polls);self.sent=[];self.closed=False
    def send(self,value): self.sent.append(value)
    def poll(self,timeout): return next(self.polls,False)
    def recv(self): return {"result":{"point":(1,2)}}
    def close(self): self.closed=True


class LifecycleTest(unittest.TestCase):
    def model(self):
        model=ModelProcess.__new__(ModelProcess)
        model.lock=threading.Lock();model.timeout=45;model.refine=True
        model.process=Process();model.connection=Connection([True])
        model.alias="gui-owl-2b";model.weights="unused";model.max_pixels=1048576
        return model

    def test_cancelled_request_is_not_replayed_and_next_request_lazily_recovers(self):
        model=self.model();old=model.connection
        with self.assertRaises(CancelledError): model.infer(b"png","old",10,20,cancelled=lambda:True)
        self.assertEqual([],old.sent)
        self.assertFalse(model.process.is_alive())
        def start(deadline,cancelled):
            model.process=Process();model.connection=Connection([True])
        with patch.object(model,"_start",side_effect=start) as restart:
            result=model.infer(b"new","new",10,20)
        restart.assert_called_once()
        self.assertEqual((1,2),result["point"])
        self.assertEqual("new",model.connection.sent[0]["target"])
        self.assertTrue(model.connection.sent[0]["refine"])

    def test_cancellation_while_waiting_stops_worker_before_result_delivery(self):
        model=self.model();model.connection=Connection([False,True])
        checks=iter([False,False,True])
        with self.assertRaises(CancelledError): model.infer(b"png","target",10,20,cancelled=lambda:next(checks,True))
        self.assertFalse(model.process.is_alive())
        self.assertFalse(model.lock.locked())

    def test_total_deadline_covers_request_and_next_request_can_recover(self):
        model=self.model();model.timeout=1;model.connection=Connection([False])
        with patch("inference.time.monotonic",side_effect=[0,0,0.5,1.1]):
            with self.assertRaises(TimeoutError): model.infer(b"png","target",10,20)
        self.assertFalse(model.process.is_alive())
        def start(deadline,cancelled):
            model.process=Process();model.connection=Connection([True])
        with patch.object(model,"_start",side_effect=start):
            self.assertEqual((1,2),model.infer(b"new","new",10,20)["point"])

    def test_busy_request_cannot_start_a_second_worker(self):
        model=self.model();model.process.alive=False;model.lock.acquire()
        with patch.object(model,"_start") as start:
            with self.assertRaises(BusyError): model.infer(b"png","target",10,20)
        start.assert_not_called()
        model.lock.release()

    def test_real_spawned_worker_recovers_after_timeout_and_cancellation_without_replaying_old_jobs(self):
        with patch("inference.worker",isolated_worker):
            model=ModelProcess("gui-owl-2b","unused",timeout=5,refine=True)
            try:
                first_pid=model.process.pid
                model.timeout=.15
                with self.assertRaises(TimeoutError): model.infer(b"png","slow",10,20)
                self.assertFalse(model.process.is_alive())
                model.timeout=5
                self.assertEqual("new-after-timeout",model.infer(b"new","new-after-timeout",10,20)["target_seen"])
                self.assertNotEqual(first_pid,model.process.pid)
                second_pid=model.process.pid
                cancelled=threading.Event()
                timer=threading.Timer(.15,cancelled.set);timer.start()
                try:
                    with self.assertRaises(CancelledError): model.infer(b"png","slow",10,20,cancelled=cancelled.is_set)
                finally: timer.cancel()
                self.assertFalse(model.process.is_alive())
                self.assertEqual("new-after-cancel",model.infer(b"new","new-after-cancel",10,20)["target_seen"])
                self.assertNotEqual(second_pid,model.process.pid)
            finally:
                model.close()


if __name__=="__main__": unittest.main()
