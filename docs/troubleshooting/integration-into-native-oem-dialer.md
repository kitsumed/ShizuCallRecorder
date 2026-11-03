# Integration Into Native OEM Dialer
### Can we integrate specific file name formats to support native phone application integrations?

While we do not and will not offer pre-defined templates for specific apps, we provide the ability to customize your filename format by including multiple details like the hour, contact name, or, when available, the name of the application that made the call.

### List of templates

This is a list of templates reported to work on native phone applications by some our our users.

| Target OEM / OS Version | Template Type | Required Storage Path | Template Format | Filename Example |
| :--- | :--- | :--- | :--- | :--- |
| Honor (MagicOS 10 / Android 16) | **Standard (Number only)** | `/storage/emulated/0/Sounds/CallRecord/` | `{phone_number}_{date:year}{date:month}{date:day}{date:hours}{date:minutes}{date:seconds}` | `+33612345678_20260719093015.ogg` |
| Honor (MagicOS 10 / Android 16) | **With Contact Name** | `/storage/emulated/0/Sounds/CallRecord/` | `{caller_name}@{phone_number}_{date:year}{date:month}{date:day}{date:hours}{date:minutes}{date:seconds}` | `JohnDoe@+33612345678_20260719093015.ogg` |