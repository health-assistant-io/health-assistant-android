---
name: Bug report
about: Something doesn't work as expected
labels: bug
body:
  - type: markdown
    attributes:
      value: |
        Thanks for taking the time to report! Before you file, please check
        [the troubleshooting page](https://github.com/health-assistant-io/health-assistant-android/blob/main/docs/user/troubleshooting.md)
        — the most common problems (connection, sync, crashes) have fixes there.
  - type: input
    id: version
    attributes:
      label: App version
      description: Profile → About (or the release you downloaded)
      placeholder: "1.2.0"
    validations:
      required: true
  - type: input
    id: android
    attributes:
      label: Android version + device
      placeholder: "Android 14, POCO X3 (MIUI)"
    validations:
      required: true
  - type: dropdown
    id: mode
    attributes:
      label: App mode
      options:
        - Simple
        - Advanced
        - Not sure
  - type: textarea
    id: what-happened
    attributes:
      label: What happened?
      description: What you did, what you expected, what happened instead. Steps in order help a lot.
    validations:
      required: true
  - type: textarea
    id: logs
    attributes:
      label: Relevant output (optional)
      description: |
        Sync screen text, the staleness chip's wording, or — for crashes —
        `adb logcat -b crash -d` output. NEVER paste your connect code, API
        secret, or server URL credentials.
