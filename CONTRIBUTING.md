# Contributing

Use `android/` for the Android client and `windows/` for the Windows client. Native inference source remains under `external/`; retain its notices and follow its `AGENTS.md` when editing it.

Run the platform build scripts with `-Verify`. Keep network provider tests isolated from paid accounts. Do not commit credentials, audio recordings, models, signing files or generated build directories.

Preserve the Android application ID and loopback protocol for existing upgrades and integrations unless a change includes an explicit compatibility plan. Starting the app or saving settings must not automatically start its local HTTP listener.
