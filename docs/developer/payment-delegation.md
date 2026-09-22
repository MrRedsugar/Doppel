# Delegated Payment

The Android Settings page includes **Allow Delegated Payment**, off by default.
The user must complete three risk acknowledgements, each with a five-second
foreground countdown. Backgrounding, cancellation, loss of focus, or recreation
discards unfinished consent. The feature is available in both bundled clients.

| Local setting | Ask / Assist | Full access |
| --- | --- | --- |
| Off | Manual payment handoff | Manual payment handoff |
| On | Manual payment handoff | May submit an ordinary purchase payment with `pay` |

This authorizes actions within the user's task; it does not authorize arbitrary
purchases. Payment passwords, financial OTPs, biometrics, transfers, remittances,
and setup of persistent debit authority remain manual. MCP and native plugins
do not gain an independent payment capability or a way to change this setting.

## Host Contract

A determines from the current screenshot and user intent whether the next action
actually submits payment, then chooses `pay`. Browsing orders, payment history or
checkout details still uses `tap`, with no extra `safety` field. In A/B mode, B
only locates the tap target; it cannot change A's payment decision. The Python MCP
adapter likewise accepts `act(action="pay")` using the existing target parameters.
Host/device code does not infer payment from labels, descriptions or page text.

An observation may contain `payment_consent_id`, with the supported format
`payment-v1:<lowercase UUID>`. Missing or null means disabled. For a `full` task,
the runtime copies this value onto a `pay` command and supplies the stored task
mode. The `act` caller cannot provide either field, even as null. The model sees
only the enabled/disabled capability, not the consent identifier. `ask`/`assist`
cannot authorize payment through their ordinary approval flow.

Android is the final authority. It checks the persisted local consent again at
the point of action, compares its generation with the command, and checks the
current source context. Relaxed target revalidation is unavailable for payment.
Turning the setting off invalidates queued commands locally without depending
on the gateway connection. Turning it on again creates a new generation.

The non-exported Settings Activity pauses device work and refuses mutation by
Doppel's executor. Sensitive controls refuse accessibility click actions;
obscured touches are rejected, and Android 12+ hides application overlays.
These controls protect against the project's agent/tools, not a rooted device,
ADB administrator, or malicious code already executing inside the host process.

## Persistence and Repeated Actions

Consent requires matching versioned records in a local SQLite database and an
independent active-grant file under `noBackupFilesDir`. Disabling removes the grant
and clears the database record; either successful revocation makes the old consent
unusable after restart. When neither can be persisted, the current process blocks
payment and displays a failure with a retry-close action. Do not describe total
storage failure as a successfully saved revocation.

Before a payment click, Android durably claims the task/application pair. It will
not attempt payment again in that application during the same task, even if the
command ID, label, amount, resource ID, or consent generation changes. This is
deliberately conservative without a trustworthy transaction ID: multi-step
payment flows within one application may need human continuation. Starting a new
task is not transaction deduplication; verify prior orders before trying again.

The existing command ledger separately prevents transport replay after a crash.
An accepted Android click is not proof of a charge. The task must inspect the
subsequent payment/order state and never infer merchant acceptance from an
accessibility return value. Uncertain attempts require human takeover.

## Compatibility and Verification

Update the gateway and Android client together. Old versions do not support
`pay`; missing consent remains disabled. Legacy consent-stamped `tap` commands
are blocked rather than replayed, and unknown consent versions are not silently
granted. Public SDK integrations must
implement the same host enforcement, rather than inventing an ID at registration.

Payment recognition depends on A's understanding of the screenshot and intended
action. The host enforces permissions and execution boundaries, not a second
keyword classifier. Model judgment can still be wrong on misleading interfaces.
Settings consent does not establish compatibility with every payment provider.

The 2026-09-07 JD meal was manually recovered and paid by the development operator
only after a specific user payment request. It is not autonomous Doppel payment
acceptance. Engineering tests exercise the actual settings/executor against an
isolated counter with no financial provider; actual merchant validation requires
a separately authorized real purchase.
