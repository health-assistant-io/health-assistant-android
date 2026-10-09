---
name: Feature request
about: An idea or improvement for the app
labels: enhancement
body:
  - type: textarea
    id: problem
    attributes:
      label: What are you trying to do?
      description: The goal, not the solution — "I want to see my morning BP trend at a glance" beats "add X toggle to screen Y".
    validations:
      required: true
  - type: textarea
    id: solution
    attributes:
      label: How might it work?
      description: Your idea, if you have one — rough is fine.
  - type: checkboxes
    id: checks
    attributes:
      label: Checks
      options:
        - label: I checked the [feature catalog](https://github.com/health-assistant-io/health-assistant-android/blob/main/docs/user/features.md) — it isn't already there.
          required: true
        - label: This is about the Android app, not the server (server ideas go to [health-assistant](https://github.com/health-assistant-io/health-assistant)).
          required: true
