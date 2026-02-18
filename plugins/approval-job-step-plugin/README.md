# Approval Job Step Plugin (Fixed)

This plugin adds an approval gate to a Rundeck workflow step.

## What It Does

- Sends an approval email to the primary approver.
- Optionally escalates to a secondary approver after `escalationTimeMinutes`.
- Adds approve/deny links with tokenized callbacks.
- Waits for callback response and then:
  - continues on `approved`
  - fails on `denied`
  - times out (or auto-approves if configured)

## Key Fixes Included

- Fixed `StepException` usage for Rundeck 5.x compatibility.
- Fixed key storage password retrieval using Rundeck API (no reflective access).
- Fixed typed config handling (`Integer`/`Boolean` fields).
- Added callback receiver on port `5555`.
- Added multipart email support (HTML + plain text fallback).
- Added professional HTML template with white + green styling.
- Added user dropdowns for approver emails (from Rundeck users).

## Configuration Fields

- `approvalMessage` (required)
- `approvalTimeoutMinutes`
- `autoApproveOnTimeout`
- `primaryApproverEmail` (required, dropdown + free input)
- `secondaryApproverEmail` (dropdown + free input)
- `escalationTimeMinutes`
- `smtpServer` (required)
- `smtpPort`
- `smtpUsername` (required)
- `smtpPasswordPath` (required, e.g. `keys/quicknet/QuickNet Mail`)
- `fromEmailAddress` (required)
- `useTls`
- `approvalUrlBase` (for local demo: `http://localhost:5555`)
- `checkIntervalSeconds`

## Local Demo Requirements

1. Docker compose must expose callback port:

```yaml
ports:
  - "4440:4440"
  - "5555:5555"
```

2. Job step `approvalUrlBase` should be:

```text
http://localhost:5555
```

3. Run job and click approve/deny link from the same machine.

## Build

This plugin is standalone and can be built with:

```bash
source "/Users/mvanson/Documents/Rundeck OSS Projects/rundeck/.java11-env.sh"
"/Users/mvanson/Documents/Rundeck OSS Projects/rundeck/gradlew" -p "/Users/mvanson/Documents/Rundeck OSS Projects/rundeck/plugins/approval-job-step-plugin" clean build --no-daemon
```

Output jar:

```text
plugins/approval-job-step-plugin/build/libs/approval-job-step-fixed-3.0.8.jar
```

## Deploy

Copy jar into Rundeck libext (mounted folder), then recreate container:

```bash
cp "plugins/approval-job-step-plugin/build/libs/approval-job-step-fixed-3.0.8.jar" \
   "../rundeck-docker-prod/rundeck_home/libext/approval-job-step-3.0.8.jar"

cd "../rundeck-docker-prod"
docker compose up -d --force-recreate
```

## Notes

- If links still show old host, update the active workflow step row in DB (or edit step in UI and save).
- If SMTP fails, verify DNS/port reachability from inside container.
- Approver dropdowns are loaded from the `rduser` table using `RUNDECK_DATABASE_URL`, `RUNDECK_DATABASE_USERNAME`, and `RUNDECK_DATABASE_PASSWORD`.
