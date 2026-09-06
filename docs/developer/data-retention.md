# Task Data and Screenshot Retention

The authenticated gateway exposes owner-scoped data management. Default retention
is seven days after the task's latest persisted event; days=0 keeps completed task
data until explicit deletion. Allowed values are integer 0 through 3650. Nonterminal
tasks, workers still shutting down and in-flight model requests awaiting usage
settlement are never deleted automatically.

| Route under /v1 | Behavior |
| --- | --- |
| GET /data-retention | {days, cleanup_pending, device_cleanup_pending} |
| PATCH /data-retention | Set {days}; no unknown fields |
| DELETE /runs/{run_id} | Delete a terminal, stopped task's server data |
| GET /runs/{run_id}/screenshots | {items:[{id,command_id,created_at,mime_type,size,digest}]} |
| GET /runs/{run_id}/screenshots/{id} | Original image bytes with no-store and nosniff headers |
| DELETE /runs/{run_id}/screenshots/{id} | Delete that screenshot's server copies |

The screenshot ID is its command ID. Listings contain at most 200 items, no image
bytes. Images are limited to 8 MiB decoded and PNG/JPEG/WebP signatures; arbitrary
HTML/SVG payloads are not served. Authentication and owner lookup precede reads.
The server does not transcode or repair malformed image content. Only raw images
actually present in command results can be listed; successful deletion produces
404 on subsequent image reads. Missing/foreign tasks return 404.

Deletion returns {status:"deleted"|"pending", cleanup_pending:boolean,
device_cleanup_required:true}. A still-running task/worker or unsettled in-flight
model request returns 409. Filesystem
or WAL checkpoint failure returns 202/pending; it is not successful erasure.
Settings report remaining cleanup counts. Pending task data is unavailable through
the screenshot routes while recovery runs. The normal task history may still show
a pending record until its database removal completes.

## What Is Removed

Task deletion removes server commands and their raw screenshot/observation data,
events, current observation, task record, in-memory task token and the task's
Harness session folder (including model-context copies and worker log). The
persisted task token is revoked before cleanup begins. Independent owner settings,
devices, authorized document files/outputs, managed long-term memories and billing
ledgers are not erased by task deletion; those resources have separate ownership
and management. Data already sent to a model/provider is subject to that service's
retention terms and cannot be revoked by deleting a local copy.

Individual screenshot deletion removes matching raw base64 from command payloads
and results, task payload/observation and event messages/data. Exact copies of the
same screenshot in that task are removed together. The whole stopped Harness
session directory is removed because its internal context may retain image copies;
the task's nonimage history stays available. It does not claim to remove derived
natural-language descriptions or every possible transformed/reencoded image.
Command IDs remain recorded to prevent replay. The closed task token is revoked.

The manager enables SQLite secure_delete and checkpoints/truncates WAL before
reporting server cleanup complete. This removes application-managed database
copies, not disk snapshots, operating-system caches, filesystem backups, SSD
wear-leveling remnants or copies made by another program. Do not claim forensic
secure erasure. Sensitive backups need their own explicit retention policy.

## Recovery and Device Copies

Cleanup intent is persisted in retention_cleanup before filesystem deletion.
Filesystem and database-checkpoint phases are separate and recoverable. A locked
file or checkpoint leaves the job queued with its error class and attempt count.
Recovery is idempotent; concurrent cleanup passes share a runtime lock. Router
lifespan runs recovery and age-based pruning at startup and every sixty seconds,
then waits for in-progress cleanup before shutdown. Deploy one runtime owner per
data directory. Settings and pending queues survive normal service restarts.

Server cleanup creates a durable device outbox because an offline phone may retain
raw command-result data. The authenticated device client consumes:

| Route under /v1 | Behavior |
| --- | --- |
| GET /devices/{device_id}/data-cleanup | {items:[{id,run_id,kind,command_ids,created_at}]} |
| POST /devices/{device_id}/data-cleanup/{id}/ack | Confirm local raw copies were purged |

kind is run or screenshots. The client removes local screenshot/observation/result
bytes and any corresponding image cache, while preserving command-ID deduplication
tombstones. It must never replay a command merely because its original result was
purged. An already acknowledged result may be represented locally by an uncertain
tombstone/hash; sending a changed duplicate to this server returns 409 rather than
executing it. The device acknowledges cleanup only after successful local removal.
device_cleanup_pending remains nonzero until then. HTTP image responses forbid
caching; a custom client must clear its own UI/disk caches as well.

Document downloads are independently managed: deleting a document requires clearing
the corresponding app cache, and task/screenshot cleanup does not revoke a WPS or
other receiving application's own saved copy. Long-term memory and external
provider copies are likewise separate. These limits must be visible in product
data-management semantics, not hidden behind a blanket "all data erased" claim.

## Python Integration

get_retention_manager(runtime) returns the cached RetentionManager. Its synchronous
methods are settings/update_settings, screenshots/screenshot/delete_screenshot,
delete_run, recover, prune, device_cleanup and ack_device_cleanup. All owner-facing
methods require an authenticated owner. create_retention_router is included in the
normal public gateway and composes with private services without importing or
deleting private billing data. Tests use disposable runtime databases and exercise
deletion, owner isolation, locked-file recovery, checkpoint recovery, concurrent
cleanup, retention defaults/forever and device acknowledgements.
