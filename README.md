# Device Status Checker

A Hubitat app that audits selected devices and presents their key settings in a sortable, filterable table. Useful for reviewing device configuration across your entire device list at a glance.


![Device Status Checker screenshot](Screenshot%202026-05-08%20153422.png)
---

## Installation

1. In Hubitat, go to **Apps Code → + New App** and paste in the contents of `Device_Status_Checker_1_12.groovy`.
2. Save. OAuth will be enabled automatically on first use.
3. Go to **Apps → + Add User App** and select **Device Status Checker**.
4. Expand the **Device Selection** section and choose the devices you want to audit.

---

## Columns

| Column | Description |
|---|---|
| **Device ID** | Internal Hubitat device ID; links to the device edit page |
| **Device Name** | Device label |
| **Device Type** | Driver name |
| **Disabled** | Whether the device is disabled — click to toggle |
| **Hub Mesh** | Whether Hub Mesh is enabled for this device — click to toggle (shows `—` for devices that do not support it) |
| **Command Retry** | Whether Command Retry is enabled — click to toggle (shows `—` for devices that do not support it) |
| **Event History Size** | Maximum number of events retained for this device |
| **State History Size** | Maximum number of states retained for this device |
| **Events Alert Threshold** | "Too many events" alert threshold |
| **Logging** | Driver logging preferences found in the device's settings: `logEnable` controls debug logging; `txtEnable` controls description text logging. ✓ = enabled, ✗ = disabled |

---

## Features

### Sorting
Click any column header to sort by that column; click again to reverse direction. The default sort is by Device Name. Numeric columns (Device ID, history sizes, alert threshold) sort numerically. The Logging column sorts by count of enabled logging preferences.

### Filtering and hiding rows
- The **Filter** field at the top right of the table accepts wildcard patterns: `*` matches any sequence of characters, `?` matches any single character (e.g. `*Motion*`). Filtering is case-insensitive.
- The **Disabled devices** row-hide button hides all rows where Disabled is Yes. Click it again to show them.
- Both the filter and the row-hide button apply together — a row must pass both to be visible.

### Column visibility
The **Hide columns** bar above the table allows individual columns to be shown or hidden. Button state is persisted in browser localStorage and restored on every page load. A strikethrough button indicates that column is currently hidden.

### In-place toggles
Cells in the **Disabled**, **Hub Mesh**, and **Command Retry** columns are clickable to toggle the setting directly without leaving the app. The Disabled toggle calls Hubitat's internal `/device/disable` endpoint. The Hub Mesh and Command Retry toggles use a read-then-write pattern: the current device state and version are fetched from `/device/fullJson/{id}` first, then the full device record is re-submitted to `/device/update` with only the changed field updated.

### Rescan
Click **Rescan Devices** to re-run the audit immediately. The report is also rebuilt automatically whenever the page is opened or the device list is changed. The scan duration is shown next to the Last scan timestamp.

### Printable report and CSV export
After the first scan, links appear in the **Controls** section:
- **📄 Open Printable Report** — opens a print-ready HTML page in a new browser tab.
- **⬇ Download CSV** — downloads the current data as `DeviceState.csv`.

Both use data from the most recent scan; no rescan is triggered by opening them. These links use Hubitat's OAuth API, which is enabled automatically on first install. If auto-setup fails, enable OAuth manually in Apps Code and re-open the app.

### App instance name
The app's display name can be changed in the **Controls** section using the **App instance name** field.

### Debug logging
An **Enable debug logging** toggle is available in the **Controls** section. Debug logging auto-disables after 30 minutes.

---

## Logging column details

The Logging column searches the `settings[]` array in `/device/fullJson/{deviceId}` for `bool`-type preferences whose name contains *debug*, *log*, *trace*, *verbose*, or *txt*. The two most common preferences found are:

- **`logEnable`** — enables verbose `log.debug` messages for troubleshooting. Most drivers that include this preference auto-disable it after 30 minutes.
- **`txtEnable`** — enables `log.info` description text messages for routine state changes (e.g. *"Office Lamp was turned on"*).

Devices showing `—` in this column have no matching boolean preferences. Note that `settings[]` may not expose every driver preference for all device types.

---

## API notes

This app uses several Hubitat internal endpoints that are not part of a formal public API and may change in a future platform release:

| Endpoint | Used for |
|---|---|
| `GET /device/fullJson/{id}` | Reading device settings including Hub Mesh, Command Retry, retention values, and logging preferences |
| `POST /device/disable` | Toggling the Disabled state |
| `POST /device/update` | Toggling Hub Mesh and Command Retry |

---

## Version history

| Version | Changes |
|---|---|
| 1.12 | Logging column: reads driver bool preferences from `settings[]` in `/device/fullJson`; shows each as name ✓/✗; sorts by count of enabled prefs |
| 1.11 | Hub Mesh and Command Retry columns with in-place toggle via `POST /device/update` |
| 1.10 | App instance name field; printable HTML report and CSV export via OAuth endpoints |
| 1.9 | Name filter field with wildcard support; Hide Rows button for disabled devices; Rescan Devices button |
| 1.8 | Sortable columns; persistent column-hide toggle bar; Disabled cell toggle; gold header row; scan duration |
