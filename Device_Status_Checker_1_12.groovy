/*
 *  Device Status Checker
 *
 *  Hubitat app:
 *    - User selects devices using capability.*
 *    - Reports:
 *        Device ID
 *        Device Name
 *        Device Type
 *        Disabled status (clickable to toggle)
 *        Hub Mesh status (On/Off. clickable to toggle)
 *        Command Retry status (On/Off for devices supporting the function, clickable to toggle)
 *        Event history size
 *        State history size
 *        Too many events alert threshold
 *
 *  Notes:
 *    - Event/state/threshold fields are not documented DeviceWrapper getters.
 *    - This app first tries undocumented/direct object access.
 *    - If that fails, it uses Hubitat's internal device JSON endpoint:
 *          /device/fullJson/{deviceId}
 *    - The Disabled cell toggle uses Hubitat's internal endpoint:
 *          /device/disable
 *    - Hubitat's internal endpoints are not formal public APIs and may change.
 *
 *  v1.12 — Logging column: reads driver bool preferences whose name contains
 *          "debug", "log", "trace", "verbose", or "txt" from /device/fullJson
 *          settings[]; shows each as name ✓/✗; sorts by count of enabled prefs;
 *          shows — for devices with no matching preferences
 *  v1.11 — Hub Mesh and Command Retry columns: read from /device/fullJson and
 *          toggle via POST /device/update (read-version-then-resubmit pattern);
 *          cells show — for devices where the feature is not available
 *  v1.10 — App instance name field; printable HTML report and CSV export via OAuth
 *          endpoints; scan rows cached in state so report/CSV never need a rescan
 *  v1.9 — Name filter field (wildcard * and ?); Hide Rows button for disabled
 *         devices; Rescan Devices button below Devices section; Device Type
 *         column left-justified
 *  v1.8 — Removed Source/Notes column; sortable columns; persistent column-hide
 *         toggle bar (localStorage); Disabled cells clickable to toggle state;
 *         gold header row matching Rule Logging Status Checker; removed Diagnostics
 *         section; Notes section moved to end; scan duration added to Last scan
 *         line; Enable debug logging toggle added at bottom
 */

import groovy.transform.Field

@Field static final String HUB_BASE_URL = "http://127.0.0.1:8080"

definition(
    name:        "Device Status Checker 1.12",
    namespace:   "johnland",
    author:      "John Land & AI",
    description: "Report for selected devices for disabled status and history retention settings.",
    category:    "Convenience",
    iconUrl:     '',
    iconX2Url:   '',
    singleInstance: true,
    oauth:          true
)

preferences {
    page(name: "mainPage", title: "<b>Device Status Checker</b>", install: true, uninstall: true)
}

// ── OAuth endpoint mapping ────────────────────────────────────────────────────
mappings {
    path("/report")          { action: [GET: "handleReportEndpoint"] }
    path("/DeviceState.csv") { action: [GET: "handleCsvEndpoint"] }
}

def installed() {
    checkOAuth()
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    // No subscriptions needed.
    if (debugEnable) {
        runIn(1800, "logsOff")
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    log.info "Device Status Checker: debug logging auto-disabled"
}

void appButtonHandler(String btn) {
    // "btnRescan" just needs the page to re-render — buildReportHtml() runs fresh
    // on every mainPage() call, so the page refresh triggered here is the rescan.
    if (debugEnable) log.debug "appButtonHandler: ${btn}"
}

// ============================================================
// OAuth — self-enabling, same pattern as Rule Logging checker
// ============================================================

// Step 1: look up this app's type ID from /hub2/userAppTypes.
private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: HUB_BASE_URL, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == app.name }
            if (match) typeId = match.id?.toString()
        }
    } catch (Exception e) {
        if (debugEnable) log.debug "getAppTypeId: could not fetch user app types — ${e.message}"
    }
    return typeId
}

// Step 2: POST to /app/edit/update to enable OAuth on this app's code.
private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) {
        log.warn "autoEnableOAuth: could not determine app type ID — enable OAuth manually in Apps Code"
        return false
    }
    String internalVer = null
    try {
        httpGet([uri: HUB_BASE_URL, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        log.error "autoEnableOAuth: could not fetch app code version — ${e.message}"
        return false
    }
    if (!internalVer) { log.error "autoEnableOAuth: app code version was null"; return false }
    boolean success = false
    try {
        httpPost([
            uri                : HUB_BASE_URL,
            path               : "/app/edit/update",
            requestContentType : "application/x-www-form-urlencoded",
            body               : [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout            : 20
        ]) { resp -> success = true }
        if (success) log.info "autoEnableOAuth: OAuth enabled on app code (typeId: ${typeId})"
    } catch (Exception e) {
        log.error "autoEnableOAuth: POST to /app/edit/update failed — ${e.message}"
    }
    return success
}

// Step 3: called from installed() and mainPage(). Returns true when a token exists.
boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        if (state.accessToken) { log.info "Device Status Checker: OAuth token created"; return true }
    } catch (Exception e) {
        if (debugEnable) log.debug "checkOAuth: OAuth not yet enabled — attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) { log.info "Device Status Checker: OAuth auto-enabled and token created"; return true }
            } catch (Exception e2) {
                log.error "checkOAuth: token creation still failed after auto-enable — ${e2.message}"
            }
        }
    }
    return false
}

// ── OAuth endpoint handlers ───────────────────────────────────────────────────

def handleReportEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/html; charset=UTF-8", data: buildPrintHtml()
}

def handleCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildCsv()
}

// ── Printable HTML report ─────────────────────────────────────────────────────
private String buildPrintHtml() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "<table><thead><tr>"
    ["Device ID","Device Name","Device Type","Disabled","Hub Mesh","Cmd Retry","Event Hist Size","State Hist Size","Alert Threshold","Logging"].each {
        sb << "<th>${htmlEscape(it)}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        String dis = safeString(r.disabled)
        String disFmt = (dis == "Yes") ? "<span style='color:red;font-weight:bold'>Yes</span>"
                                       : "<span style='color:green'>No</span>"
        String meshFmt  = (r.meshAvailable  == true) ? ((r.meshEnabled  == true) ? "<span style='color:green;font-weight:bold'>On</span>"  : "Off") : "—"
        String retryFmt = (r.retryAvailable == true) ? ((r.retryEnabled == true) ? "<span style='color:green;font-weight:bold'>On</span>"  : "Off") : "—"
        sb << "<tr>"
        sb << "<td class='c'>${htmlEscape(safeString(r.id))}</td>"
        sb << "<td>${htmlEscape(safeString(r.name))}</td>"
        sb << "<td>${htmlEscape(safeString(r.type))}</td>"
        sb << "<td class='c'>${disFmt}</td>"
        sb << "<td class='c'>${meshFmt}</td>"
        sb << "<td class='c'>${retryFmt}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.eventHistorySize))}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.stateHistorySize))}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.tooManyEventsThreshold))}</td>"
        List<Map> rls = (r.loggingSettings instanceof List) ? (List<Map>) r.loggingSettings : []
        String rLogFmt = rls.isEmpty() ? "—" : rls.collect { Map ls ->
            "${htmlEscape(safeString(ls.name as String))} ${ls.enabled == true ? '✓' : '✗'}"
        }.join("<br>")
        sb << "<td>${rLogFmt}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: 'never'} &mdash; ${rows.size()} device${rows.size() == 1 ? '' : 's'}"
    return """<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>Device State Table</title>
<style>
  body{font-family:sans-serif;font-size:0.9em;margin:20px;}
  h2{margin-bottom:4px;}
  .sub{color:#555;font-size:0.9em;margin-bottom:12px;}
  table{border-collapse:collapse;width:100%;}
  th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:middle;}
  th{background:#FFD700;font-weight:bold;}
  td.c{text-align:center;}
  @media print{a{text-decoration:none;}}
</style></head><body>
<h2>Device State Table</h2>
<div class='sub'>${subtitle}</div>
${sb}
</body></html>"""
}

// ── CSV export ────────────────────────────────────────────────────────────────
private String buildCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "Device ID,Device Name,Device Type,Disabled,Hub Mesh,Cmd Retry,Event Hist Size,State Hist Size,Alert Threshold,Logging\n"
    rows.each { Map r ->
        String meshVal  = (r.meshAvailable  == true) ? ((r.meshEnabled  == true) ? "On" : "Off") : "—"
        String retryVal = (r.retryAvailable == true) ? ((r.retryEnabled == true) ? "On" : "Off") : "—"
        List<Map> cls = (r.loggingSettings instanceof List) ? (List<Map>) r.loggingSettings : []
        String logVal = cls.isEmpty() ? "—" : cls.collect { Map ls ->
            "${ls.name}:${ls.enabled == true ? 'on' : 'off'}"
        }.join("; ")
        sb << "${escapeCsv(r.id)},${escapeCsv(r.name)},${escapeCsv(r.type)}"
        sb << ",${escapeCsv(r.disabled)}"
        sb << ",${meshVal}"
        sb << ",${retryVal}"
        sb << ",${escapeCsv(r.eventHistorySize)}"
        sb << ",${escapeCsv(r.stateHistorySize)}"
        sb << ",${escapeCsv(r.tooManyEventsThreshold)}"
        sb << ",${escapeCsv(logVal)}\n"
    }
    return sb.toString()
}

private String escapeCsv(Object val) {
    String s = val == null ? "" : val.toString()
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
    return s
}

def mainPage() {
    checkOAuth()
    dynamicPage(name: "mainPage", title: "<b>${app.name}</b>", install: true, uninstall: true) {

        section("Device Selection", hideable: true, hidden: true) {
            input(
                name           : "selectedDevices",
                type           : "capability.*",
                title          : "Select devices to audit",
                multiple       : true,
                required       : false,
                submitOnChange : true
            )
        }

        section("") {
            input "btnRescan", "button", title: "Rescan Devices"
        }

        section("") {
            paragraph buildReportHtml()
        }

        section("Controls", hideable: true, hidden: true) {
            // ── App instance rename ───────────────────────────────────────────
            input "label", "text", title: "<b>App instance name</b>", defaultValue: app.name, submitOnChange: true

            // ── Report and CSV links ──────────────────────────────────────────
            if (state.accessToken) {
                String reportUrl = "/apps/api/${app.id}/report?access_token=${state.accessToken}"
                String csvUrl    = "/apps/api/${app.id}/DeviceState.csv?access_token=${state.accessToken}"
                if (state.scanRowsJson) {
                    paragraph "<b>Device State Table</b> &nbsp;" +
                        "<a href='${reportUrl}' target='_blank'>&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${csvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Click <b>Rescan Devices</b> to enable the report and CSV export.</small>"
                }
            } else {
                paragraph "<small>OAuth setup required before reports are available. " +
                          "Re-open the app to retry automatic setup, or enable OAuth manually " +
                          "in Apps Code.</small>"
            }

            // ── Debug logging ─────────────────────────────────────────────────
            input(
                name: "debugEnable",
                type: "bool",
                title: "<b>Enable debug logging</b>",
                defaultValue: false,
                required: false,
                submitOnChange: true
            )
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph(
                "<div style='font-size:0.92em;color:#555;'>" +
                "<b>Data sources</b><br>" +
                "Direct DeviceWrapper checks run first for Event History Size, State History Size, " +
                "and Too Many Events Alert Threshold. Any values not available directly are read " +
                "from <code>${HUB_BASE_URL}/device/fullJson/{deviceId}</code>.<br><br>" +
                "<b>Rescan</b><br>" +
                "Click <b>Rescan Devices</b> to re-run the audit. The report is also rebuilt " +
                "automatically whenever the page is opened or the Devices list is changed.<br><br>" +
                "<b>Printable report and CSV export</b><br>" +
                "Links appear in the <b>Controls</b> section after the first scan. The printable " +
                "report opens in a new browser tab. The CSV download is named DeviceState.csv. " +
                "Both use data from the most recent scan — no rescan is triggered by opening them. " +
                "These links use Hubitat's OAuth API and are self-enabling; if setup fails the " +
                "app will prompt you to enable OAuth manually in Apps Code.<br><br>" +
                "<b>Filter and hide rows</b><br>" +
                "The <b>Filter</b> field accepts wildcard patterns: <b>*</b> matches any sequence " +
                "of characters, <b>?</b> matches any single character (e.g. <code>*Motion*</code>). " +
                "Filtering is case-insensitive. The <b>Disabled devices</b> row button hides all " +
                "rows where Disabled is Yes; click it again to show them. Both filters apply " +
                "together — a row must pass both to be visible.<br><br>" +
                "<b>Disabled toggle</b><br>" +
                "Click any cell in the Disabled column to toggle that device's disabled state. " +
                "Uses Hubitat's internal <code>/device/disable</code> endpoint.<br><br>" +
                "<b>Column hide buttons</b><br>" +
                "The Hide columns bar above the table persists across page reloads using browser " +
                "localStorage. Each button toggles visibility of its column; a strikethrough " +
                "button means that column is currently hidden.<br><br>" +
                "<b>Sorting</b><br>" +
                "Click any column header to sort by that column. Click again to reverse the sort " +
                "direction. Numeric columns (Device ID, history sizes, threshold) sort numerically.<br><br>" +
                "<b>API note</b><br>" +
                "Hubitat internal endpoints are not formal public APIs and may change in a future " +
                "platform release.<br><br>" +
                "<b>Logging column</b><br>" +
                "Shows driver preferences from <code>settings[]</code> in <code>/device/fullJson</code> " +
                "whose name contains <i>debug</i>, <i>log</i>, <i>trace</i>, <i>verbose</i>, or <i>txt</i> " +
                "and whose type is <code>bool</code>. A ✓ means enabled, ✗ means disabled. " +
                "The column sorts by count of enabled prefs. Devices showing — have no matching " +
                "preferences; note that <code>settings[]</code> may not expose every driver preference " +
                "for all device types." +
                "</div>"
            )
        }
    }
}

/* --------------------------------------------------------------------------
 * Report generation
 * -------------------------------------------------------------------------- */

private String buildReportHtml() {
    List devs = normalizeDeviceList(selectedDevices)

    if (!devs) {
        return "<p>Select one or more devices above.</p>"
    }

    Long scanStart = now() as Long

    List<Map> rows = []
    devs.sort { a, b ->
        safeString(getDeviceName(a)) <=> safeString(getDeviceName(b))
    }.each { dev ->
        rows << auditDevice(dev)
    }

    Long scanMs = (now() as Long) - scanStart
    String duration = formatScanDuration(scanMs)

    StringBuilder sb = new StringBuilder()

    // ── CSS + JS ──────────────────────────────────────────────────────────────
    sb << buildDeviceReportCssJs()

    // ── Summary line ──────────────────────────────────────────────────────────
    sb << "<div style='margin-bottom:28px;'>"
    sb << "<b>Devices scanned:</b> ${rows.size()}&emsp;"
    sb << "<b>Last scan:</b> ${htmlEscape(nowString())} (Scan time: ${duration})"
    sb << "</div>"

    // ── Column hide toggle bar + row filter + name filter ─────────────────────
    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<b>Hide rows:</b>&nbsp;"
    sb << "<span id='dsc-toggle-row-disabled' class='rmcol-btn' data-pref-key='dsc-rowfilt-disabled' onclick='dscToggleRowFilter(this)'>Disabled devices</span>"
    sb << "&nbsp;&nbsp;<b>Hide columns:</b>&nbsp;"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-devid'     data-col-class='dsc-col-devid'     onclick=\"dscToggleCol('dsc-col-devid',this)\">Device ID</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-devtype'   data-col-class='dsc-col-devtype'   onclick=\"dscToggleCol('dsc-col-devtype',this)\">Device Type</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-evtsize'   data-col-class='dsc-col-evtsize'   onclick=\"dscToggleCol('dsc-col-evtsize',this)\">Event Hist Size</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-statesize' data-col-class='dsc-col-statesize' onclick=\"dscToggleCol('dsc-col-statesize',this)\">State Hist Size</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-threshold' data-col-class='dsc-col-threshold' onclick=\"dscToggleCol('dsc-col-threshold',this)\">Alert Threshold</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-mesh'      data-col-class='dsc-col-mesh'      onclick=\"dscToggleCol('dsc-col-mesh',this)\">Hub Mesh</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-retry'     data-col-class='dsc-col-retry'     onclick=\"dscToggleCol('dsc-col-retry',this)\">Cmd Retry</span>"
    sb << "<span class='rmcol-btn' data-pref-key='dsc-logging'   data-col-class='dsc-col-logging'   onclick=\"dscToggleCol('dsc-col-logging',this)\">Logging</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='dsc-name-filter' type='text' class='rmname-filter' placeholder='Name (* and ? wildcards)' oninput='applyDscRowFilters()' style='width:230px;'>"
    sb << "</div>"

    // ── Table ─────────────────────────────────────────────────────────────────
    sb << "<table id='dsc_table' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',0)\" class='center dsc-col-devid'>Device ID</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',1)\" class='sort-asc'>Device Name</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',2)\" class='dsc-col-devtype'>Device Type</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',3)\" class='center'>Disabled</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',4)\" class='center dsc-col-mesh'>Hub Mesh</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',5)\" class='center dsc-col-retry'>Cmd Retry</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',6)\" class='center dsc-col-evtsize'>Event Hist Size</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',7)\" class='center dsc-col-statesize'>State Hist Size</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',8)\" class='center dsc-col-threshold'>Alert Threshold</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',9)\" class='dsc-col-logging'>Logging</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id          = htmlEscape(safeString(r.id))
        String nameEsc     = htmlEscape(safeString(r.name))
        String typeEsc     = htmlEscape(safeString(r.type))
        String evtSize     = htmlEscape(safeString(r.eventHistorySize))
        String stateSize   = htmlEscape(safeString(r.stateHistorySize))
        String threshold   = htmlEscape(safeString(r.tooManyEventsThreshold))
        boolean isDisabled = (r.disabled == "Yes")
        String disabledFmt = isDisabled
            ? "<span style='color:red;font-weight:bold;'>Yes</span>"
            : "<span style='color:green;font-weight:bold;'>No</span>"

        // Build device name cell with link
        String sid = safeString(r.id)
        String nameLinkHtml
        if (sid && sid != "unknown") {
            String href = "/device/edit/${htmlEscape(urlEncodePathSegment(sid))}"
            nameLinkHtml = "<a href='${href}' target='_blank'>${nameEsc}</a>"
        } else {
            nameLinkHtml = nameEsc
        }

        sb << "<tr${isDisabled ? " class='dsc-row-disabled'" : ""}>"
        sb << "<td class='center dsc-col-devid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameEsc}'>${nameLinkHtml}</td>"
        sb << "<td class='dsc-col-devtype' data-sort='${typeEsc}'>${typeEsc}</td>"
        sb << "<td class='center rmlog-clickable' data-sort='${isDisabled ? "1" : "0"}' data-device-id='${id}' data-on='${isDisabled}' onclick='devToggleDisabled(this)'>${disabledFmt}</td>"

        // Hub Mesh cell
        if (r.meshAvailable == true) {
            boolean meshOn = (r.meshEnabled == true)
            String meshFmt = meshOn
                ? "<span style='color:green;font-weight:bold;'>On</span>"
                : "<span style='color:#888;'>Off</span>"
            sb << "<td class='center dsc-col-mesh rmlog-clickable' data-sort='${meshOn ? "1" : "0"}' data-device-id='${id}' data-field='meshEnabled' data-on='${meshOn}' onclick='devToggleDeviceProp(this)'>${meshFmt}</td>"
        } else {
            sb << "<td class='center dsc-col-mesh' data-sort=''><span style='color:#bbb;'>—</span></td>"
        }

        // Command Retry cell
        if (r.retryAvailable == true) {
            boolean retryOn = (r.retryEnabled == true)
            String retryFmt = retryOn
                ? "<span style='color:green;font-weight:bold;'>On</span>"
                : "<span style='color:#888;'>Off</span>"
            sb << "<td class='center dsc-col-retry rmlog-clickable' data-sort='${retryOn ? "1" : "0"}' data-device-id='${id}' data-field='retryEnabled' data-on='${retryOn}' onclick='devToggleDeviceProp(this)'>${retryFmt}</td>"
        } else {
            sb << "<td class='center dsc-col-retry' data-sort=''><span style='color:#bbb;'>—</span></td>"
        }
        sb << "<td class='center dsc-col-evtsize'   data-sort='${evtSize}'>${evtSize}</td>"
        sb << "<td class='center dsc-col-statesize' data-sort='${stateSize}'>${stateSize}</td>"
        sb << "<td class='center dsc-col-threshold' data-sort='${threshold}'>${threshold}</td>"

        // Logging settings cell
        List<Map> logSettings = (r.loggingSettings instanceof List) ? (List<Map>) r.loggingSettings : []
        if (logSettings.isEmpty()) {
            sb << "<td class='dsc-col-logging' data-sort=''><span style='color:#bbb;'>—</span></td>"
        } else {
            int enabledCount = logSettings.count { it.enabled == true } as int
            List<String> parts = logSettings.collect { Map ls ->
                boolean on = (ls.enabled == true)
                String col = on ? "color:green;" : "color:#888;"
                String sym = on ? "✓" : "✗"
                "<span style='font-size:0.88em;${col}'>${htmlEscape(safeString(ls.name as String))} ${sym}</span>"
            }
            sb << "<td class='dsc-col-logging' data-sort='${enabledCount}'>${parts.join('<br>')}</td>"
        }
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    // Restore any previously saved column-hide preferences from localStorage
    sb << "<script>dscLoadPrefs();</script>"

    // Cache rows so the OAuth report/CSV endpoints can serve without a rescan
    try {
        state.scanRowsJson = groovy.json.JsonOutput.toJson(rows)
        state.lastScan     = nowString()
    } catch (Exception e) {
        if (debugEnable) log.debug "buildReportHtml: could not cache scan rows — ${e.message}"
    }

    return sb.toString()
}

/* --------------------------------------------------------------------------
 * CSS and JavaScript for the report table
 * -------------------------------------------------------------------------- */

private String buildDeviceReportCssJs() {
    StringBuilder sb = new StringBuilder()

    // ── CSS — mirrors Rule Logging Status Checker styling ─────────────────────
    sb << "<style>"
    sb << "table.rmlogcheck{border-collapse:collapse;width:100%;}"
    sb << "table.rmlogcheck th,table.rmlogcheck td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}"
    sb << "table.rmlogcheck th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}"
    sb << "table.rmlogcheck th:hover{background-color:#FFC700;}"
    sb << "table.rmlogcheck th.sort-asc::after{content:' ▲';font-size:0.8em;}"
    sb << "table.rmlogcheck th.sort-desc::after{content:' ▼';font-size:0.8em;}"
    sb << "table.rmlogcheck td.center,table.rmlogcheck th.center{text-align:center;}"
    sb << ".rmcol-toggle-bar{margin-bottom:8px;font-size:0.9em;}"
    sb << ".rmcol-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;"
    sb << "border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}"
    sb << ".rmcol-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}"
    sb << "table.rmlogcheck td.rmlog-clickable{cursor:pointer;}"
    sb << "table.rmlogcheck td.rmlog-clickable:hover{filter:brightness(0.82);}"
    sb << "table.rmlogcheck td.rmlog-toggling{opacity:0.45;cursor:wait;pointer-events:none;}"
    sb << ".rmname-filter{padding:2px 6px;font-size:0.9em;border:1px solid #aaa;border-radius:3px;vertical-align:middle;}"
    sb << "</style>"

    // ── JavaScript ────────────────────────────────────────────────────────────
    sb << '''<script>
// ── Column sort ──────────────────────────────────────────────────────────────
function sortRmLogTable(tableId, columnIndex) {
    var table = document.getElementById(tableId);
    if (!table) return;
    var tbody = table.querySelector('tbody');
    if (!tbody) return;
    var rows = Array.from(tbody.querySelectorAll('tr'));
    var headers = table.querySelectorAll('th');
    if (!window.rmLogTableSorts) window.rmLogTableSorts = {};
    if (!window.rmLogTableSorts[tableId]) window.rmLogTableSorts[tableId] = {};
    var currentDir = window.rmLogTableSorts[tableId][columnIndex] || 'asc';
    var newDir = (currentDir === 'asc') ? 'desc' : 'asc';
    window.rmLogTableSorts[tableId][columnIndex] = newDir;
    headers.forEach(function(h) { h.classList.remove('sort-asc', 'sort-desc'); });
    if (headers[columnIndex]) headers[columnIndex].classList.add('sort-' + newDir);
    var numPat = /^-?\\d+(\\.\\d+)?$/;
    rows.sort(function(a, b) {
        var aC = a.querySelectorAll('td')[columnIndex];
        var bC = b.querySelectorAll('td')[columnIndex];
        var aT = aC ? (aC.getAttribute('data-sort') || aC.textContent || '').trim() : '';
        var bT = bC ? (bC.getAttribute('data-sort') || bC.textContent || '').trim() : '';
        var cmp = (numPat.test(aT) && numPat.test(bT))
            ? (parseFloat(aT) - parseFloat(bT))
            : aT.toLowerCase().localeCompare(bT.toLowerCase());
        return newDir === 'asc' ? cmp : -cmp;
    });
    rows.forEach(function(row) { tbody.appendChild(row); });
}

// ── Column hide toggle — persists to localStorage ─────────────────────────────
function dscToggleCol(cls, btn) {
    var hiding = !btn.classList.contains('hidden-col');
    document.querySelectorAll('.' + cls).forEach(function(el) {
        el.style.display = hiding ? 'none' : '';
    });
    btn.classList.toggle('hidden-col', hiding);
    try { localStorage.setItem('dsc_pref_' + btn.dataset.prefKey, String(hiding)); } catch(e) {}
}

// ── Wildcard name filter ──────────────────────────────────────────────────────
// Convert a wildcard pattern (* = any chars, ? = any single char) to a RegExp.
function wildcardToRegex(pattern) {
    var result = '';
    for (var i = 0; i < pattern.length; i++) {
        var ch = pattern[i];
        if (ch === '*') { result += '.*'; }
        else if (ch === '?') { result += '.'; }
        else if ('.+^${}()|[]\\\\'.indexOf(ch) >= 0) { result += '\\\\' + ch; }
        else { result += ch; }
    }
    return new RegExp('^' + result + '$', 'i');
}

// ── Row filter — evaluates the Disabled button and name filter together ────────
function applyDscRowFilters() {
    var btn = document.getElementById('dsc-toggle-row-disabled');
    var hideDisabled = btn && btn.classList.contains('hidden-col');
    var filterEl = document.getElementById('dsc-name-filter');
    var filterVal = filterEl ? filterEl.value.trim() : '';
    var filterRe = null;
    if (filterVal) {
        try { filterRe = wildcardToRegex(filterVal); } catch(e) { filterRe = null; }
    }
    document.querySelectorAll('#dsc_table tbody tr').forEach(function(tr) {
        var hide = hideDisabled && tr.classList.contains('dsc-row-disabled');
        if (!hide && filterRe) {
            var nameCell = tr.querySelectorAll('td')[1];
            var nm = nameCell ? (nameCell.getAttribute('data-sort') || nameCell.textContent || '').trim() : '';
            if (!filterRe.test(nm)) hide = true;
        }
        tr.style.display = hide ? 'none' : '';
    });
}

// ── Row filter button toggle — persists to localStorage ───────────────────────
function dscToggleRowFilter(btn) {
    btn.classList.toggle('hidden-col');
    applyDscRowFilters();
    try { localStorage.setItem('dsc_pref_' + btn.dataset.prefKey, String(btn.classList.contains('hidden-col'))); } catch(e) {}
}

// Restore saved column-hide and row-filter preferences from localStorage
function dscLoadPrefs() {
    // Column hide buttons
    document.querySelectorAll('[data-pref-key][data-col-class]').forEach(function(btn) {
        var stored;
        try { stored = localStorage.getItem('dsc_pref_' + btn.dataset.prefKey); } catch(e) {}
        if (stored === 'true') {
            var cls = btn.dataset.colClass;
            document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = 'none'; });
            btn.classList.add('hidden-col');
        }
    });
    // Row filter button
    var rowBtn = document.getElementById('dsc-toggle-row-disabled');
    if (rowBtn) {
        var stored;
        try { stored = localStorage.getItem('dsc_pref_' + rowBtn.dataset.prefKey); } catch(e) {}
        if (stored === 'true') rowBtn.classList.add('hidden-col');
    }
    // Apply row filters in case the row filter button was restored as active
    applyDscRowFilters();
}

// ── Disabled cell toggle ─────────────────────────────────────────────────────
// Calls Hubitat's internal /device/disable endpoint (not a public API).
async function devToggleDisabled(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var deviceId = td.dataset.deviceId;
    var newOn = (td.dataset.on !== 'true');
    try {
        var resp = await fetch('/device/disable', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ id: deviceId, disable: String(newOn) }).toString()
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn
            ? "<span style='color:red;font-weight:bold;'>Yes</span>"
            : "<span style='color:green;font-weight:bold;'>No</span>";
        var tr = td.closest('tr');
        if (tr) {
            if (newOn) tr.classList.add('dsc-row-disabled');
            else       tr.classList.remove('dsc-row-disabled');
        }
        applyDscRowFilters();
    } catch(e) {
        alert('Toggle disabled failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

// ── Hub Mesh / Command Retry toggle ──────────────────────────────────────────
// Requires a read-then-write: fetch /device/fullJson first to get the current
// version (concurrency guard) and all device fields, then re-POST /device/update
// with every field intact and only the one target field changed.
// Hubitat uses checkbox semantics: enabled = 'on', disabled = 'false' (not 'true').
async function devToggleDeviceProp(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var deviceId  = td.dataset.deviceId;
    var fieldName = td.dataset.field;   // 'meshEnabled' or 'retryEnabled'
    var newOn     = (td.dataset.on !== 'true');
    try {
        // Step 1 — read current state + version
        var jr = await fetch('/device/fullJson/' + deviceId);
        if (!jr.ok) throw new Error('fullJson HTTP ' + jr.status);
        var data = await jr.json();
        var dev = data.device;
        if (!dev) throw new Error('No device object in fullJson');

        // Step 2 — build full re-POST body (Hubitat requires all device fields)
        var bv = function(v) { return v ? 'on' : 'false'; };
        var fd = new URLSearchParams();
        fd.set('id',                     String(dev.id));
        fd.set('version',                String(dev.version));
        fd.set('label',                  dev.label                  || dev.displayName || '');
        fd.set('name',                   dev.name                   || '');
        fd.set('deviceNetworkId',        dev.deviceNetworkId        || '');
        fd.set('deviceTypeId',           String(dev.deviceTypeId    || ''));
        fd.set('deviceTypeReadableType', dev.deviceTypeReadableType || '');
        fd.set('controllerType',         dev.controllerType         || '');
        fd.set('hubId',                  String(dev.hubId           != null ? dev.hubId      : 1));
        fd.set('locationId',             String(dev.locationId      != null ? dev.locationId : 1));
        fd.set('groupId',                String(dev.groupId         != null ? dev.groupId    : 0));
        fd.set('roomId',                 String(dev.roomId          != null ? dev.roomId     : 0));
        fd.set('maxEvents',              String(dev.maxEvents       != null ? dev.maxEvents        : ''));
        fd.set('maxStates',              String(dev.maxStates       != null ? dev.maxStates        : ''));
        fd.set('spammyThreshold',        String(dev.spammyThreshold != null ? dev.spammyThreshold  : ''));
        fd.set('defaultIcon',            dev.defaultIcon            || '');
        fd.set('notes',                  dev.notes                  || '');
        fd.set('tags',                   dev.tags                   || '');
        fd.set('dashboardIds',           '');
        fd.set('homeKitEnabled',         bv(dev.homeKitEnabled));
        fd.set('meshFullSync',           bv(dev.meshFullSync));
        // Toggle the target field; keep the other at its current value
        fd.set('meshEnabled',  fieldName === 'meshEnabled'  ? bv(newOn) : bv(dev.meshEnabled));
        fd.set('retryEnabled', fieldName === 'retryEnabled' ? bv(newOn) : bv(dev.retryEnabled));
        if (dev.zigbeeId) fd.set('zigbeeId', dev.zigbeeId);

        // Step 3 — POST; Hubitat responds with HTTP 302 redirect on success
        var pr = await fetch('/device/update', {
            method:  'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body:    fd.toString()
        });
        if (!pr.ok) throw new Error('device/update HTTP ' + pr.status);

        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn
            ? "<span style='color:green;font-weight:bold;'>On</span>"
            : "<span style='color:#888;'>Off</span>";
    } catch(e) {
        alert('Toggle failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}
</script>'''

    return sb.toString()
}

/* --------------------------------------------------------------------------
 * Device audit
 * -------------------------------------------------------------------------- */

private Map auditDevice(dev) {
    String id       = safeString(getDeviceId(dev))
    String name     = safeString(getDeviceName(dev))
    String type     = safeString(getDeviceType(dev))
    String disabled = getDisabledStatus(dev)

    Map direct      = getRetentionValuesDirectly(dev)
    Map jsonValues  = [:]

    // Always fetch fullJson — needed for Hub Mesh, Command Retry, and retention
    // values that aren't exposed via the DeviceWrapper directly.
    if (id) {
        Map jsonResult = fetchDeviceFullJson(id)
        if (jsonResult.ok) {
            jsonValues = jsonResult.values ?: [:]
        } else if (debugEnable) {
            log.debug "auditDevice: fetchDeviceFullJson failed for device ${id} — ${jsonResult.error ?: 'unknown error'}"
        }
    }

    Object eventSize  = firstUseful(direct.eventHistorySize,       jsonValues.eventHistorySize,       "unknown")
    Object stateSize  = firstUseful(direct.stateHistorySize,       jsonValues.stateHistorySize,       "unknown")
    Object threshold  = firstUseful(direct.tooManyEventsThreshold, jsonValues.tooManyEventsThreshold, "unknown")

    return [
        id                     : id       ?: "unknown",
        name                   : name     ?: "unknown",
        type                   : type     ?: "unknown",
        disabled               : disabled,
        meshEnabled            : jsonValues.meshEnabled,           // Boolean or null
        meshAvailable          : jsonValues.meshSelectionEnabled,  // Boolean or null
        retryEnabled           : jsonValues.retryEnabled,          // Boolean or null
        retryAvailable         : jsonValues.retryAvailable,        // Boolean or null
        loggingSettings        : jsonValues.loggingSettings ?: [],  // List<Map> of {name, enabled}
        eventHistorySize       : eventSize,
        stateHistorySize       : stateSize,
        tooManyEventsThreshold : threshold
    ]
}

private boolean valuesNeedLookup(Map values) {
    return isBlankValue(values?.eventHistorySize) ||
           isBlankValue(values?.stateHistorySize) ||
           isBlankValue(values?.tooManyEventsThreshold)
}

private boolean hasAnyLookupValue(Map values) {
    return !isBlankValue(values?.eventHistorySize) ||
           !isBlankValue(values?.stateHistorySize) ||
           !isBlankValue(values?.tooManyEventsThreshold)
}

/* --------------------------------------------------------------------------
 * DeviceWrapper field helpers
 * -------------------------------------------------------------------------- */

private List normalizeDeviceList(Object obj) {
    if (obj == null) return []
    if (obj instanceof Collection) return obj as List
    return [obj]
}

private Object getDeviceId(dev) {
    return firstUseful(
        tryMethod(dev, "getId"),
        tryMethod(dev, "getIdAsLong"),
        tryProperty(dev, "id")
    )
}

private Object getDeviceName(dev) {
    return firstUseful(
        tryMethod(dev, "getName"),
        tryProperty(dev, "name"),
        tryMethod(dev, "getDisplayName"),
        tryProperty(dev, "displayName")
    )
}

private Object getDeviceType(dev) {
    return firstUseful(
        tryMethod(dev, "getTypeName"),
        tryProperty(dev, "typeName"),
        tryProperty(dev, "deviceTypeName"),
        tryProperty(dev, "driverName"),
        tryProperty(dev, "type")
    )
}

private String getDisabledStatus(dev) {
    Object val = firstUseful(
        tryMethod(dev, "isDisabled"),
        tryMethod(dev, "getDisabled"),
        tryMethod(dev, "getIsDisabled"),
        tryProperty(dev, "disabled"),
        tryProperty(dev, "isDisabled")
    )

    if (val == null) return "unknown"

    String s = val.toString().toLowerCase()
    if (s in ["true", "yes", "1"]) return "Yes"
    if (s in ["false", "no", "0"]) return "No"

    return val.toString()
}

/*
 * Try likely undocumented/internal field names.
 * Some hubs/versions may expose none of these.
 */
private Map getRetentionValuesDirectly(dev) {
    return [
        eventHistorySize: findFirstDirectValue(dev, [
            "eventHistorySize",
            "eventsHistorySize",
            "eventHistoryLimit",
            "eventHistoryMaxSize",
            "maxEventHistorySize",
            "maxEvents",
            "eventLimit",
            "maxEventLimit",
            "eventHistory"
        ]),
        stateHistorySize: findFirstDirectValue(dev, [
            "stateHistorySize",
            "statesHistorySize",
            "deviceStateHistorySize",
            "stateHistoryLimit",
            "stateHistoryMaxSize",
            "maxStateHistorySize",
            "maxStates",
            "stateLimit",
            "stateHistory"
        ]),
        tooManyEventsThreshold: findFirstDirectValue(dev, [
            "tooManyEventsThreshold",
            "tooManyAlertsThreshold",
            "tooManyEventsAlertThreshold",
            "spammyThreshold",
            "eventAlertThreshold",
            "eventsAlertThreshold",
            "alertThreshold",
            "tooManyEvents",
            "tooManyAlerts",
            "eventThreshold",
            "eventsThreshold",
            "excessiveEventsThreshold",
            "excessiveEventThreshold",
            "excessiveEventAlertThreshold",
            "maxEventThreshold",
            "maxEventsThreshold",
            "eventWarnThreshold",
            "eventWarningThreshold"
        ])
    ]
}

private Object findFirstDirectValue(dev, List<String> names) {
    for (String n : names) {
        Object v = firstUseful(
            tryMethod(dev, "get${upperFirst(n)}"),
            tryMethod(dev, n),
            tryProperty(dev, n),
            tryDeviceSetting(dev, n)
        )

        if (!isBlankValue(v)) {
            return v
        }
    }

    return null
}

private Object tryDeviceSetting(dev, String settingName) {
    try {
        return dev?.getSetting(settingName)
    } catch (Throwable ignored) {
        return null
    }
}

private Object tryMethod(Object obj, String methodName) {
    try {
        if (obj == null || methodName == null) return null

        def methods = obj.metaClass.respondsTo(obj, methodName)
        if (methods) {
            return obj."${methodName}"()
        }
    } catch (Throwable ignored) {
        return null
    }

    return null
}

private Object tryProperty(Object obj, String propName) {
    try {
        if (obj == null || propName == null) return null
        return obj."${propName}"
    } catch (Throwable ignored) {
        return null
    }
}

/* --------------------------------------------------------------------------
 * Device JSON endpoint probing
 * -------------------------------------------------------------------------- */

private Map fetchDeviceFullJson(String deviceId) {
    Map out = [
        ok: false,
        hasValues: false,
        status: null,
        error: null,
        values: [:]
    ]

    try {
        httpGet([
            uri: HUB_BASE_URL,
            path: "/device/fullJson/${deviceId}",
            timeout: 15,
            contentType: "application/json"
        ]) { resp ->
            out.status = resp?.status

            if (resp?.status == 200) {
                Object data = resp?.data

                if (data instanceof String) {
                    try {
                        data = new groovy.json.JsonSlurper().parseText(data as String)
                    } catch (Throwable parseError) {
                        out.error = "JSON parse failed: ${parseError.message}"
                    }
                }

                if (data != null) {
                    Map values = parseDeviceFullJson(data)
                    out.ok        = true
                    out.values    = values
                    out.hasValues = hasAnyLookupValue(values)
                    if (debugEnable) {
                        log.debug "fetchDeviceFullJson: device ${deviceId} — eventHistorySize=${values.eventHistorySize}, stateHistorySize=${values.stateHistorySize}, tooManyEventsThreshold=${values.tooManyEventsThreshold}"
                    }
                }
            }
        }
    } catch (Throwable e) {
        out.error = e?.message ?: e.toString()
        if (debugEnable) {
            log.debug "fetchDeviceFullJson: device ${deviceId} — caught exception: ${out.error}"
        }
    }

    return out
}

private Map parseDeviceFullJson(Object jsonData) {
    Map result = [
        eventHistorySize: findJsonNumericValue(jsonData, [
            "eventHistorySize",
            "eventsHistorySize",
            "eventHistory",
            "eventsHistory",
            "eventHistoryLimit",
            "eventHistoryMaxSize",
            "maxEventHistorySize",
            "maxEvents",
            "eventLimit",
            "maxEventLimit"
        ]),
        stateHistorySize: findJsonNumericValue(jsonData, [
            "stateHistorySize",
            "statesHistorySize",
            "deviceStateHistorySize",
            "stateHistory",
            "statesHistory",
            "stateHistoryLimit",
            "stateHistoryMaxSize",
            "maxStateHistorySize",
            "maxStates",
            "stateLimit"
        ]),
        tooManyEventsThreshold: firstUseful(
            findJsonNumericValue(jsonData, [
                "tooManyEventsThreshold",
                "tooManyAlertsThreshold",
                "tooManyEventsAlertThreshold",
                "spammyThreshold",
                "eventAlertThreshold",
                "eventsAlertThreshold",
                "alertThreshold",
                "tooManyEvents",
                "tooManyAlerts",
                "eventThreshold",
                "eventsThreshold",
                "excessiveEventsThreshold",
                "excessiveEventThreshold",
                "excessiveEventAlertThreshold",
                "maxEventThreshold",
                "maxEventsThreshold",
                "eventWarnThreshold",
                "eventWarningThreshold"
            ]),
            findJsonNumericValueByLabel(jsonData, [
                "Too many events alert threshold",
                "Too Many Events Alert Threshold",
                "Too many alerts threshold",
                "Too many events threshold",
                "Too many alerts",
                "Too many events",
                "Excessive events threshold",
                "Event alert threshold"
            ]),
            findJsonThresholdHeuristic(jsonData)
        )
    ]

    // Hub Mesh and Command Retry live directly under data.device (not nested deeper).
    // meshSelectionEnabled / retryAvailable indicate whether the feature applies to this device.
    if (jsonData instanceof Map) {
        Object devNode = ((Map) jsonData).get("device")
        if (devNode instanceof Map) {
            Map dev = (Map) devNode
            result.meshEnabled          = asBoolOrNull(dev.get("meshEnabled"))
            result.meshSelectionEnabled = asBoolOrNull(dev.get("meshSelectionEnabled"))
            result.retryEnabled         = asBoolOrNull(dev.get("retryEnabled"))
            result.retryAvailable       = asBoolOrNull(dev.get("retryAvailable"))
        }
    }

    // Logging prefs — scan settings[] for bool entries with logging-related names
    result.loggingSettings = parseLoggingSettings(jsonData)

    return result
}

private String findJsonNumericValue(Object node, List<String> candidateNames) {
    Set<String> normalizedNames = candidateNames.collect { normalizeKey(it) } as Set
    return findJsonNumericValueRecursive(node, normalizedNames, 0)
}

private String findJsonNumericValueRecursive(Object node, Set<String> normalizedNames, int depth) {
    if (node == null || depth > 10) return null

    if (node instanceof Map) {
        for (Object entryObj : ((Map) node).entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj
            String key      = entry.key?.toString()
            Object val      = entry.value

            if (key && normalizedNames.contains(normalizeKey(key))) {
                String cleaned = cleanNumericValue(val)
                if (cleaned   != null) return cleaned
            }
        }

        for (Object val : ((Map) node).values()) {
            String found = findJsonNumericValueRecursive(val, normalizedNames, depth + 1)
            if (found   != null) return found
        }
    } else if (node instanceof Collection) {
        for (Object val : (Collection) node) {
            String found = findJsonNumericValueRecursive(val, normalizedNames, depth + 1)
            if (found   != null) return found
        }
    }

    return null
}

private String normalizeKey(Object key) {
    if (key == null) return ""
    return key.toString().toLowerCase().replaceAll(/[^a-z0-9]/, "")
}

private String cleanNumericValue(Object val) {
    if (val == null) return null
    if (val instanceof Boolean) return null
    if (val instanceof Number) return val.toString()

    String s = val.toString().trim().replaceAll(/[,]/, "")
    if (s ==~ /^-?\d+(?:\.\d+)?$/) return s

    return null
}

private String findJsonNumericValueByLabel(Object node, List<String> labelPhrases) {
    Set<String> normalizedLabels = labelPhrases.collect { normalizeKey(it) } as Set
    return findJsonNumericValueByLabelRecursive(node, normalizedLabels, 0)
}

private String findJsonNumericValueByLabelRecursive(Object node, Set<String> normalizedLabels, int depth) {
    if (node == null || depth > 10) return null

    if (node instanceof Map) {
        Map m = (Map) node
        String labelText = firstUseful(
            getMapValueByNormalizedKey(m, ["name"]),
            getMapValueByNormalizedKey(m, ["label"]),
            getMapValueByNormalizedKey(m, ["title"]),
            getMapValueByNormalizedKey(m, ["displayName"]),
            getMapValueByNormalizedKey(m, ["description"]),
            getMapValueByNormalizedKey(m, ["text"])
        )?.toString()

        if (labelText && normalizedLabels.any { normalizeKey(labelText).contains(it) }) {
            Object value = firstUseful(
                getMapValueByNormalizedKey(m, ["value"]),
                getMapValueByNormalizedKey(m, ["currentValue"]),
                getMapValueByNormalizedKey(m, ["settingValue"]),
                getMapValueByNormalizedKey(m, ["defaultValue"]),
                getMapValueByNormalizedKey(m, ["threshold"]),
                getMapValueByNormalizedKey(m, ["val"])
            )

            String cleaned = cleanNumericValue(value)
            if (cleaned != null) return cleaned

            for (Object entryObj : m.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj
                String keyNorm  = normalizeKey(entry.key)
                if (!(keyNorm in ["id", "deviceid", "name", "label", "title", "displayname", "description", "text"])) {
                    cleaned = cleanNumericValue(entry.value)
                    if (cleaned != null) return cleaned
                }
            }
        }

        for (Object val : m.values()) {
            String found = findJsonNumericValueByLabelRecursive(val, normalizedLabels, depth + 1)
            if (found   != null) return found
        }
    } else if (node instanceof Collection) {
        for (Object val : (Collection) node) {
            String found = findJsonNumericValueByLabelRecursive(val, normalizedLabels, depth + 1)
            if (found   != null) return found
        }
    }

    return null
}

private Object getMapValueByNormalizedKey(Map m, List<String> candidateKeys) {
    Set<String> wanted = candidateKeys.collect { normalizeKey(it) } as Set
    for (Object entryObj : m.entrySet()) {
        Map.Entry entry = (Map.Entry) entryObj
        if (wanted.contains(normalizeKey(entry.key))) return entry.value
    }
    return null
}

private String findJsonThresholdHeuristic(Object node) {
    return findJsonThresholdHeuristicRecursive(node, 0)
}

private String findJsonThresholdHeuristicRecursive(Object node, int depth) {
    if (node == null || depth > 10) return null

    if (node instanceof Map) {
        Map m = (Map) node

        for (Object entryObj : m.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj
            String keyNorm  = normalizeKey(entry.key)
            String cleaned  = cleanNumericValue(entry.value)

            if (cleaned != null && keyLooksLikeTooManyEventsThreshold(keyNorm)) {
                return cleaned
            }
        }

        for (Object val : m.values()) {
            String found = findJsonThresholdHeuristicRecursive(val, depth + 1)
            if (found   != null) return found
        }
    } else if (node instanceof Collection) {
        for (Object val : (Collection) node) {
            String found = findJsonThresholdHeuristicRecursive(val, depth + 1)
            if (found   != null) return found
        }
    }

    return null
}

private boolean keyLooksLikeTooManyEventsThreshold(String keyNorm) {
    if (!keyNorm) return false
    if (keyNorm.contains("history") || keyNorm.contains("state")) return false

    // Hubitat's /device/fullJson/{id} uses device.spammyThreshold for the
    // UI field labelled "Alert Threshold".
    if (keyNorm == "spammythreshold") return true

    boolean thresholdish = keyNorm.contains("threshold") || keyNorm.contains("alert")  || keyNorm.contains("excess") || keyNorm.contains("spammy")
    boolean eventish     = keyNorm.contains("event")     || keyNorm.contains("events") || keyNorm.contains("alert")  || keyNorm.contains("excess") || keyNorm.contains("spammy")

    return thresholdish && eventish
}

/* --------------------------------------------------------------------------
 * General helpers
 * -------------------------------------------------------------------------- */

private String urlEncodePathSegment(String s) {
    try {
        return java.net.URLEncoder.encode(s ?: "", "UTF-8").replace("+", "%20")
    } catch (Throwable ignored) {
        return s ?: ""
    }
}

private Object firstUseful(Object... vals) {
    for (Object v : vals) {
        if (!isBlankValue(v)) return v
    }
    return null
}

private boolean isBlankValue(Object v) {
    if (v == null) return true
    String s = v.toString()
    return s == null || s.trim().length() == 0 || s.trim().equalsIgnoreCase("null")
}

private String safeString(Object v) {
    return v == null ? "" : v.toString()
}

private String upperFirst(String s) {
    if (!s) return s
    if (s.length() == 1) return s.toUpperCase()
    return s.substring(0, 1).toUpperCase() + s.substring(1)
}

private String htmlEscape(Object val) {
    String s = val == null ? "" : val.toString()
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private String nowString() {
    try {
        return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    } catch (Throwable ignored) {
        return new Date().toString()
    }
}

private String formatScanDuration(Long elapsedMs) {
    Long safeMs = elapsedMs ?: 0L
    if (safeMs < 0L) safeMs = 0L
    Long totalSeconds = Math.round(safeMs / 1000.0D) as Long
    Long minutes      = Math.floor(totalSeconds / 60.0D) as Long
    Long seconds      = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

private Boolean asBoolOrNull(Object v) {
    if (v == null) return null
    if (v instanceof Boolean) return (Boolean) v
    String s = v.toString().toLowerCase().trim()
    if (s == "true"  || s == "on")  return true
    if (s == "false" || s == "off") return false
    return null
}

// Scan the settings[] array in /device/fullJson for bool preferences whose name
// contains any logging-related keyword. Returns a sorted list of {name, enabled}
// maps. Returns an empty list if none found or settings[] is absent.
// Note: settings[] may not contain every driver preference — coverage depends on
// what Hubitat exposes in fullJson for a given device type.
private List<Map> parseLoggingSettings(Object jsonData) {
    if (!(jsonData instanceof Map)) return []
    Object settingsNode = ((Map) jsonData).get("settings")
    if (!(settingsNode instanceof List)) return []

    List<String> keywords = ["debug", "log", "trace", "verbose", "txt"]
    List<Map> results = []

    for (Object item : (List) settingsNode) {
        if (!(item instanceof Map)) continue
        Map m = (Map) item
        if (m.get("type")?.toString()?.toLowerCase() != "bool") continue
        String name = m.get("name")?.toString() ?: ""
        if (!name) continue
        if (keywords.any { name.toLowerCase().contains(it) }) {
            results << [name: name, enabled: asBoolOrNull(m.get("value"))]
        }
    }
    return results.sort { it.name }
}
