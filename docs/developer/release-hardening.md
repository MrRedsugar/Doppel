# Release protection and signing

Release variants enable R8 obfuscation/resource shrinking; debug APKs deliberately retain inspection tools. JNI and documented native-plugin types are kept by the SDK's consumer rules. Keep `mapping.txt` with the exact APK digest in private release storage for crash retracing.

The product release requires `-Pdoppel.signingSha256=<64 lowercase hex certificate SHA-256>` at build time. The installed current APK signer must match this value. An unsigned or differently signed product release cannot start its worker. Debug and third-party SDK consumers retain development access. Obtain the certificate digest from the publisher-controlled keystore, never from an untrusted APK download. Changing publisher keys requires a reviewed update to this pin and Android signing lineage.

The product-only example below applies to the private workspace and does not
publish or sign. The public source export includes `:sdk`, `:developer-app` and
`:test-app`; it does not contain the private `:app` module. Third-party SDK
consumers configure signing and their publisher policy in their own application:

```powershell
./android/gradlew.bat -p android :app:assembleRelease -Pdoppel.signingSha256=$env:DOPPEL_SIGNING_SHA256
```

Sign using Android `apksigner` with passwords supplied by its `env:` input mechanism. Verify with `apksigner verify --verbose --print-certs`, inspect `android:debuggable=false`, and compare the signer to the configured digest. Do not commit the keystore, password, mapping, API key or real user data. The generated unsigned release is a verification artifact, not a distributable installation.

Provider credentials for the developer direct mode are AES-GCM encrypted with an Android Keystore key and excluded from backups. The mode additionally requires the debuggable bit and the developer manifest marker. Commercial authentication, pricing and entitlements remain server-owned; local unlimited development points cannot be treated as commercial payment credit.

These measures increase reverse-engineering cost. Any local certificate comparison or encrypted executable can ultimately be patched by someone controlling the APK. No claim of complete anti-recompilation protection is made. A future public service needs short-lived authenticated tokens, server entitlement checks, rate limits and abuse telemetry. Do not add invasive device identifiers or root/emulator bans as a substitute.

The Apache-licensed framework remains redistributable under its license. Commercial branding, signing keys and private services are separate assets; retain third-party notices and comply with every bundled model/runtime license. Company identity, service contacts and production SMS/payment/retention controls remain release gates documented in the product readiness checklist.
