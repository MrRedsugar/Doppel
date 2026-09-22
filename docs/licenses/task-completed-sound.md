# Task completion sound in the public source export

The public repository uses an original synthesized notification tone at
`android/sdk/src/main/res/raw/task_completed.wav`. `scripts/export-public.py`
generates this resource with Python's standard library from mathematical sine
waves; it does not sample, decode, transform or otherwise derive from a recording.
The synthesis code and the original generated waveform are distributed under
the project's Apache License, Version 2.0. See the root `LICENSE` in the public
export, or `framework/LICENSE` in the mixed development checkout.

The mixed checkout's `android/sdk/src/main/res/raw/task_completed.mp3` is excluded
from the public export. Its SHA-256 is
`eeba885a6fecaf9b0700b5aad6b31910c2d1a0d32fd3aa71a4a2e7233da3fb89`.
The project has not established a license permitting redistribution of that
recording as a standalone source asset. Its presence in a local build does not
grant that permission, and this document makes no license claim for it.

Both resources use Android's `raw/task_completed` resource name, so the public
build retains the existing completion notification channel and sound/vibration
controls. The public tone sounds different from the local recording. Exporting
does not alter the mixed checkout's recording or production notification code.
The generated WAV is the reviewed exception to the source export's exclusion of
downloaded binary assets; it contains no recording or user data.
