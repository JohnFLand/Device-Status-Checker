/*
 *  Device Status Checker
 *
 *  Hubitat app:
 *    - User selects devices using capability.*
 *    - Reports:
 *        Device ID
 *        Device Label
 *        Device Type
 *        Disabled status (clickable to toggle)
 *        Hub Mesh status (On/Off, clickable to toggle)
 *        Command Retry status (On/Off for devices supporting the function, clickable to toggle)
 *        Event history size
 *        State history size
 *        Too many events alert threshold
 *        Logging preferences (bool driver prefs whose name contains debug/log/trace/verbose/txt)
 *
 *  Notes:
 *    - Event/state/threshold fields are not documented DeviceWrapper getters.
 *    - This app first tries undocumented/direct object access.
 *    - If that fails, it uses Hubitat's internal device JSON endpoint:
 *          /device/fullJson/{deviceId}
 *    - The Disabled cell toggle uses Hubitat's internal endpoint:
 *          /device/disable
 *    - Hub Mesh and Command Retry toggles use a read-then-write pattern via:
 *          GET /device/fullJson/{deviceId}  then  POST /device/update
 *    - Hubitat's internal endpoints are not formal public APIs and may change.
 *
 *  See README.md for full version history.
 */

import groovy.transform.Field

@Field static final String HUB_BASE_URL       = "http://127.0.0.1:8080"
@Field static final int    SCAN_TIMEOUT_SECS  = 300   // 5-minute safety net

// Transient scan state — lives in JVM memory during the async chain.
// Not persisted; safe to lose on hub restart (finalizeScanTimeout cleans up state[]).
@Field static List<Map>        scanDeviceQueue    = []
@Field static Map<String, Map> scanPartialResults = [:]
@Field static volatile String  currentScanId      = null
@Field static volatile Long    scanStartMs        = 0L

definition(
    name:           "Device Status Checker 1.23",
    namespace:      "John Land",
    author:         "John Land & AI",
    description:    "Report for selected devices for disabled status and history retention settings.",
    category:       "Convenience",
    iconUrl:        '',
    iconX2Url:      '',
    singleInstance: true,
    oauth:          true,
    importUrl:      "https://raw.githubusercontent.com/JohnFLand/Device-Status-Checker/refs/heads/main/Device_Status_Checker.groovy"
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
    if (btn == "btnScan") startScan()
    if (debugEnable) log.debug "appButtonHandler: ${btn}"
}

private void startScan() {
    List devs = normalizeDeviceList(selectedDevices)
    if (!devs) return

    unschedule("finalizeScanTimeout")

    Long   nowMs     = now()
    String scanId    = nowMs.toString()
    String startTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    // Pre-collect everything that doesn't need an HTTP call while we still
    // have the DeviceWrapper objects (they aren't available in async callbacks).
    List<Map> queue = devs.sort { a, b ->
        safeString(getDeviceName(a)) <=> safeString(getDeviceName(b))
    }.collect { dev ->
        [
            id      : safeString(getDeviceId(dev)),
            name    : safeString(getDeviceName(dev)),
            type    : safeString(getDeviceType(dev)),
            disabled: getDisabledStatus(dev),
            direct  : getRetentionValuesDirectly(dev)
        ]
    }

    scanDeviceQueue    = queue
    scanPartialResults = [:]
    currentScanId      = scanId
    scanStartMs        = nowMs

    state.scanStatus     = "<i>Scan started: ${startTime} — scanning ${queue.size()} device${queue.size() == 1 ? '' : 's'}…</i>"
    state.scanInProgress = true

    log.info "Device Status Checker: scan started — ${queue.size()} devices (scanId: ${scanId})"
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    asynchttpGet("handleFullJsonResponse",
        [uri: HUB_BASE_URL, path: "/device/fullJson/${queue[0].id}", timeout: 30],
        [scanId: scanId, idx: 0])
}

// asynchttpGet callback — must be non-private so the platform can invoke it.
def handleFullJsonResponse(resp, Map passData) {
    String scanId = passData?.scanId as String
    int    idx    = (passData?.idx ?: 0) as int

    if (currentScanId != scanId) {
        if (debugEnable) log.debug "handleFullJsonResponse: ignoring stale callback (scanId ${scanId})"
        return
    }

    Map    qEntry   = scanDeviceQueue[idx]
    String deviceId = qEntry?.id as String

    Map jsonValues = [:]
    try {
        if (!resp.hasError() && resp.status == 200) {
            String body = resp.data
            if (body) {
                jsonValues = parseDeviceFullJson(new groovy.json.JsonSlurper().parseText(body))
            }
        } else if (debugEnable) {
            log.debug "handleFullJsonResponse: device ${deviceId} — HTTP ${resp.status} ${resp.errorMessage ?: ''}"
        }
    } catch (Exception e) {
        if (debugEnable) log.debug "handleFullJsonResponse: device ${deviceId} — ${e.message}"
    }

    Map direct = (qEntry?.direct instanceof Map) ? (Map) qEntry.direct : [:]
    scanPartialResults[deviceId] = [
        id                    : qEntry?.id       ?: "unknown",
        name                  : qEntry?.name     ?: "unknown",
        type                  : qEntry?.type     ?: "unknown",
        disabled              : qEntry?.disabled,
        meshEnabled           : jsonValues.meshEnabled,
        meshAvailable         : jsonValues.meshSelectionEnabled,
        retryEnabled          : jsonValues.retryEnabled,
        retryAvailable        : jsonValues.retryAvailable,
        loggingSettings       : jsonValues.loggingSettings ?: [],
        eventHistorySize      : firstUseful(direct.eventHistorySize,       jsonValues.eventHistorySize,       "unknown"),
        stateHistorySize      : firstUseful(direct.stateHistorySize,       jsonValues.stateHistorySize,       "unknown"),
        tooManyEventsThreshold: firstUseful(direct.tooManyEventsThreshold, jsonValues.tooManyEventsThreshold, "unknown")
    ]

    int nextIdx = idx + 1
    if (nextIdx < scanDeviceQueue.size()) {
        Map next = scanDeviceQueue[nextIdx]
        asynchttpGet("handleFullJsonResponse",
            [uri: HUB_BASE_URL, path: "/device/fullJson/${next.id}", timeout: 30],
            [scanId: scanId, idx: nextIdx])
    } else {
        finalizeScan(scanId)
    }
}

// Returns a fully-populated row map for a queue entry that received no HTTP response.
// Used by both finalizeScan() and finalizeScanTimeout() to avoid duplicating the literal.
private Map fallbackRow(Map q) {
    [
        id                    : q.id    ?: "unknown",
        name                  : q.name  ?: "unknown",
        type                  : q.type  ?: "unknown",
        disabled              : q.disabled,
        meshEnabled           : null,
        meshAvailable         : null,
        retryEnabled          : null,
        retryAvailable        : null,
        loggingSettings       : [],
        eventHistorySize      : q.direct?.eventHistorySize       ?: "unknown",
        stateHistorySize      : q.direct?.stateHistorySize       ?: "unknown",
        tooManyEventsThreshold: q.direct?.tooManyEventsThreshold ?: "unknown"
    ]
}

private void finalizeScan(String scanId) {
    if (currentScanId != scanId) return
    unschedule("finalizeScanTimeout")

    Long   elapsed  = (now() as Long) - scanStartMs
    String duration = formatScanDuration(elapsed)
    String scanTime = nowString()

    List<Map> rows = scanDeviceQueue.collect { Map q ->
        scanPartialResults[q.id as String] ?: fallbackRow(q)
    }

    try {
        state.scanRowsJson     = groovy.json.JsonOutput.toJson(rows)
        state.lastScan         = scanTime
        state.lastScanDuration = duration
    } catch (Exception e) {
        if (debugEnable) log.debug "finalizeScan: could not cache rows — ${e.message}"
    }

    currentScanId        = null
    state.scanStatus     = null
    state.scanInProgress = false

    log.info "Device Status Checker: scan completed — ${rows.size()} devices in ${duration}"
}

// Safety net in case the async chain stalls (hub restart, HTTP failure, etc.)
void finalizeScanTimeout() {
    if (!currentScanId) {
        // Hub restarted mid-scan: the @Field static is gone but persisted state flags
        // may still be set, which would blank the report section indefinitely.
        state.scanStatus     = null
        state.scanInProgress = false
        return
    }
    log.warn "Device Status Checker: scan timed out after ${SCAN_TIMEOUT_SECS}s — saving partial results"

    List<Map> rows = scanDeviceQueue.collect { Map q ->
        scanPartialResults[q.id as String] ?: fallbackRow(q)
    }
    try {
        state.scanRowsJson     = groovy.json.JsonOutput.toJson(rows)
        state.lastScan         = nowString()
        state.lastScanDuration = "timed out"
    } catch (Exception e) { }

    currentScanId        = null
    state.scanStatus     = null
    state.scanInProgress = false
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
    ["Device ID","Device Label","Device Type","Disabled","Hub Mesh","Cmd Retry","Event Hist Size","State Hist Size","Alert Threshold","Logging"].each {
        sb << "<th>${htmlEscape(it)}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        boolean isDisabled = (safeString(r.disabled) == "Yes")
        List<Map> rls = (r.loggingSettings instanceof List) ? (List<Map>) r.loggingSettings : []
        sb << "<tr>"
        sb << "<td class='c'>${htmlEscape(safeString(r.id))}</td>"
        sb << "<td>${htmlEscape(safeString(r.name))}</td>"
        sb << "<td>${htmlEscape(safeString(r.type))}</td>"
        sb << "<td class='c'>${fmtDisabled(isDisabled)}</td>"
        sb << "<td class='c'>${fmtToggle(r.meshAvailable  as Boolean, r.meshEnabled  as Boolean)}</td>"
        sb << "<td class='c'>${fmtToggle(r.retryAvailable as Boolean, r.retryEnabled as Boolean)}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.eventHistorySize))}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.stateHistorySize))}</td>"
        sb << "<td class='c'>${htmlEscape(safeString(r.tooManyEventsThreshold))}</td>"
        sb << "<td>${fmtLogPlain(rls)}</td>"
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
    sb << "Device ID,Device Label,Device Type,Disabled,Hub Mesh,Cmd Retry,Event Hist Size,State Hist Size,Alert Threshold,Logging\n"
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
    int pollInterval = currentScanId ? 5 : 0
    dynamicPage(name: "mainPage", title: "<b>${app.name}</b>", install: true, uninstall: true, refreshInterval: pollInterval) {

        int devCount = normalizeDeviceList(selectedDevices)?.size() ?: 0
        String devSectionTitle = devCount
            ? "Device Selection (${devCount} device${devCount == 1 ? '' : 's'} selected)"
            : "Device Selection"

        section(devSectionTitle, hideable: true, hidden: true) {
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
            input "btnScan", "button", title: "Scan Devices"
            if (state.scanStatus) {
                paragraph state.scanStatus
            }
        }

        section("") {
            paragraph buildReportHtml(devCount)
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
                    paragraph "<small>Click <b>Scan Devices</b> to enable the report and CSV export.</small>"
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
                "<b>Scan</b><br>" +
                "Click <b>Scan Devices</b> to run or re-run the audit. Opening the app does not " +
                "trigger a new scan — cached results from the previous scan are shown instead.<br><br>" +
                "<b>Printable report and CSV export</b><br>" +
                "Links appear in the <b>Controls</b> section after the first scan. The printable " +
                "report opens in a new browser tab. The CSV download is named DeviceState.csv. " +
                "Both use data from the most recent scan — no rescan is triggered by opening them. " +
                "These links use Hubitat's OAuth API and are self-enabling; if setup fails the " +
                "app will prompt you to enable OAuth manually in Apps Code.<br><br>" +
                "<b>Filter and hide rows</b><br>" +
                "The <b>Filter</b> field accepts plain text for case-insensitive substring matching, " +
                "so partial searches do not require wildcards. Optional wildcard patterns are also supported: " +
                "<b>*</b> matches any sequence of characters and <b>?</b> matches any single character " +
                "(e.g. <code>*Motion*</code>). The <b>Disabled devices</b> row button hides all " +
                "rows where Disabled is Yes; click it again to show them. Both filters apply " +
                "together — a row must pass both to be visible.<br><br>" +
                "<b>Disabled toggle</b><br>" +
                "Click any cell in the Disabled column to toggle that device's disabled state. " +
                "Uses Hubitat's internal <code>/device/disable</code> endpoint.<br><br>" +
                "<b>Hub Mesh and Command Retry toggles</b><br>" +
                "Click any cell in the Hub Mesh or Command Retry columns to toggle that setting. " +
                "Uses a read-then-write pattern: the current device record and version are fetched " +
                "from <code>/device/fullJson/{id}</code> first, then the full record is re-submitted " +
                "to <code>/device/update</code> with only the changed field updated. Cells showing — " +
                "indicate the device does not support that feature.<br><br>" +
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

private String buildReportHtml(int devCount) {
    if (!devCount) {
        return "<p>Select one or more devices above.</p>"
    }

    if (state.scanRowsJson) {
        List<Map> rows = []
        try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map> } catch (Exception e) {}
        return buildTableHtml(rows, state.lastScan ?: "unknown", state.lastScanDuration ?: "")
    }

    if (currentScanId) return ""   // "Scan started" in button section is enough

    return "<p>No scan results yet. Click <b>Scan Devices</b> above to run the first scan.</p>"
}

/* Renders the summary line, filter/hide bar, and data table. */
private String buildTableHtml(List<Map> rows, String lastScan, String duration) {
    StringBuilder sb = new StringBuilder()

    // ── CSS + JS ──────────────────────────────────────────────────────────────
    sb << buildDeviceReportCssJs()

    // ── Summary line ──────────────────────────────────────────────────────────
    sb << "<div style='margin-bottom:28px;'>"
    sb << "<b>Devices scanned:</b> ${rows.size()}&emsp;"
    sb << "<b>Last scan:</b> ${htmlEscape(lastScan)}"
    if (duration) {
        sb << " (Scan time: ${htmlEscape(duration)})"
    }
    sb << "</div>"

    // ── Column hide toggle bar + row filter + name filter ─────────────────────
    sb << "<div class='dsc-toggle-bar'>"
    sb << "<b>Hide rows:</b>&nbsp;"
    sb << "<span id='dsc-toggle-row-disabled' class='dsc-btn' data-pref-key='dsc-rowfilt-disabled' onclick='dscToggleRowFilter(this)'>Disabled devices</span>"
    sb << "&nbsp;&nbsp;<b>Hide columns:</b>&nbsp;"
    sb << "<span class='dsc-btn' data-pref-key='dsc-devid'     data-col-class='dsc-col-devid'     onclick=\"dscToggleCol('dsc-col-devid',this)\">Device ID</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-disabled'  data-col-class='dsc-col-disabled'  onclick=\"dscToggleCol('dsc-col-disabled',this)\">Disabled</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-devtype'   data-col-class='dsc-col-devtype'   onclick=\"dscToggleCol('dsc-col-devtype',this)\">Device Type</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-evtsize'   data-col-class='dsc-col-evtsize'   onclick=\"dscToggleCol('dsc-col-evtsize',this)\">Event Hist Size</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-statesize' data-col-class='dsc-col-statesize' onclick=\"dscToggleCol('dsc-col-statesize',this)\">State Hist Size</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-threshold' data-col-class='dsc-col-threshold' onclick=\"dscToggleCol('dsc-col-threshold',this)\">Alert Threshold</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-mesh'      data-col-class='dsc-col-mesh'      onclick=\"dscToggleCol('dsc-col-mesh',this)\">Hub Mesh</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-retry'     data-col-class='dsc-col-retry'     onclick=\"dscToggleCol('dsc-col-retry',this)\">Cmd Retry</span>"
    sb << "<span class='dsc-btn' data-pref-key='dsc-logging'   data-col-class='dsc-col-logging'   onclick=\"dscToggleCol('dsc-col-logging',this)\">Logging</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='dsc-name-filter' type='text' class='dsc-name-filter' placeholder='Name (substring or * ? wildcards)' oninput='applyDscRowFilters()' style='width:280px;'>"
    sb << "</div>"

    // ── Table ─────────────────────────────────────────────────────────────────
    sb << "<table id='dsc_table' class='dsc-table'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',0)\" class='center dsc-col-devid'>Device ID</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',1)\" class='sort-asc'>Device Label</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',2)\" class='dsc-col-devtype'>Device Type</th>"
    sb << "<th onclick=\"sortRmLogTable('dsc_table',3)\" class='center dsc-col-disabled'>Disabled</th>"
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

        // Build device label cell with link
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
        sb << "<td class='center dsc-clickable dsc-col-disabled' data-sort='${isDisabled ? "1" : "0"}' data-device-id='${id}' data-on='${isDisabled}' onclick='devToggleDisabled(this)'>${fmtDisabled(isDisabled)}</td>"

        // Hub Mesh cell
        if (r.meshAvailable == true) {
            boolean meshOn = (r.meshEnabled == true)
            sb << "<td class='center dsc-col-mesh dsc-clickable' data-sort='${meshOn ? "1" : "0"}' data-device-id='${id}' data-field='meshEnabled' data-on='${meshOn}' onclick='devToggleDeviceProp(this)'>${fmtToggle(true, meshOn)}</td>"
        } else {
            sb << "<td class='center dsc-col-mesh' data-sort=''>${fmtToggle(false, null)}</td>"
        }

        // Command Retry cell
        if (r.retryAvailable == true) {
            boolean retryOn = (r.retryEnabled == true)
            sb << "<td class='center dsc-col-retry dsc-clickable' data-sort='${retryOn ? "1" : "0"}' data-device-id='${id}' data-field='retryEnabled' data-on='${retryOn}' onclick='devToggleDeviceProp(this)'>${fmtToggle(true, retryOn)}</td>"
        } else {
            sb << "<td class='center dsc-col-retry' data-sort=''>${fmtToggle(false, null)}</td>"
        }
        sb << "<td class='center dsc-col-evtsize'   data-sort='${evtSize}'>${evtSize}</td>"
        sb << "<td class='center dsc-col-statesize' data-sort='${stateSize}'>${stateSize}</td>"
        sb << "<td class='center dsc-col-threshold' data-sort='${threshold}'>${threshold}</td>"

        // Logging settings cell
        List<Map> logSettings = (r.loggingSettings instanceof List) ? (List<Map>) r.loggingSettings : []
        int enabledCount = logSettings.count { it.enabled == true } as int
        sb << "<td class='dsc-col-logging' data-sort='${logSettings ? enabledCount : ""}'>${fmtLogHtml(logSettings)}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    // Restore any previously saved column-hide preferences from localStorage
    sb << "<script>dscLoadPrefs();</script>"

    return sb.toString()
}


/* --------------------------------------------------------------------------
 * CSS and JavaScript for the report table
 * -------------------------------------------------------------------------- */

private String buildDeviceReportCssJs() {
    StringBuilder sb = new StringBuilder()

    // ── CSS — mirrors Rule Logging Status Checker styling ─────────────────────
    sb << "<style>"
    sb << "table.dsc-table{border-collapse:collapse;width:100%;}"
    sb << "table.dsc-table th,table.dsc-table td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}"
    sb << "table.dsc-table th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}"
    sb << "table.dsc-table th:hover{background-color:#FFC700;}"
    sb << "table.dsc-table th.sort-asc::after{content:' ▲';font-size:0.8em;}"
    sb << "table.dsc-table th.sort-desc::after{content:' ▼';font-size:0.8em;}"
    sb << "table.dsc-table td.center,table.dsc-table th.center{text-align:center;}"
    sb << ".dsc-toggle-bar{margin-bottom:8px;font-size:0.9em;}"
    sb << ".dsc-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;"
    sb << "border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}"
    sb << ".dsc-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}"
    sb << "table.dsc-table td.dsc-clickable{cursor:pointer;}"
    sb << "table.dsc-table td.dsc-clickable:hover{filter:brightness(0.82);}"
    sb << "table.dsc-table td.dsc-toggling{opacity:0.45;cursor:wait;pointer-events:none;}"
    sb << ".dsc-name-filter{padding:2px 6px;font-size:0.9em;border:1px solid #aaa;border-radius:3px;vertical-align:middle;}"
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

// ── Name filter ──────────────────────────────────────────────────────────────
// Convert a wildcard pattern (* = any chars, ? = any single char) to a RegExp.
// Plain filter text is handled separately as a case-insensitive substring match.
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
    var hasWild  = filterVal.indexOf('*') >= 0 || filterVal.indexOf('?') >= 0;
    var lowerSub = '';
    if (filterVal) {
        if (hasWild) {
            // Wildcard pattern — full-string match with * and ? expansion.
            try { filterRe = wildcardToRegex(filterVal); } catch(e) { filterRe = null; }
        } else {
            // Plain text — case-insensitive substring match; no wildcards needed.
            lowerSub = filterVal.toLowerCase();
        }
    }
    document.querySelectorAll('#dsc_table tbody tr').forEach(function(tr) {
        var hide = hideDisabled && tr.classList.contains('dsc-row-disabled');
        if (!hide && filterVal) {
            var nameCell = tr.querySelectorAll('td')[1];
            var nm = nameCell ? (nameCell.getAttribute('data-sort') || nameCell.textContent || '').trim() : '';
            if      (filterRe) { hide = !filterRe.test(nm); }
            else if (lowerSub) { hide = nm.toLowerCase().indexOf(lowerSub) < 0; }
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
    td.classList.remove('dsc-clickable');
    td.classList.add('dsc-toggling');
    var deviceId = td.dataset.deviceId;
    var newOn = (td.dataset.on !== 'true');
    try {
        // Step 1 — read current state + version
        // /device/disable returns HTTP 500; use the same read-then-write pattern
        // as devToggleDeviceProp — disabled is just another device property.
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
        fd.set('hubId',                  String(dev.hubId           != null ? dev.hubId           : 1));
        fd.set('locationId',             String(dev.locationId      != null ? dev.locationId      : 1));
        fd.set('groupId',                String(dev.groupId         != null ? dev.groupId         : 0));
        fd.set('roomId',                 String(dev.roomId          != null ? dev.roomId          : 0));
        fd.set('maxEvents',              String(dev.maxEvents       != null ? dev.maxEvents       : ''));
        fd.set('maxStates',              String(dev.maxStates       != null ? dev.maxStates       : ''));
        fd.set('spammyThreshold',        String(dev.spammyThreshold != null ? dev.spammyThreshold : ''));
        fd.set('defaultIcon',            dev.defaultIcon            || '');
        fd.set('notes',                  dev.notes                  || '');
        fd.set('tags',                   dev.tags                   || '');
        fd.set('dashboardIds',           '');
        fd.set('homeKitEnabled',         bv(dev.homeKitEnabled));
        fd.set('meshFullSync',           bv(dev.meshFullSync));
        fd.set('meshEnabled',            bv(dev.meshEnabled));
        fd.set('retryEnabled',           bv(dev.retryEnabled));
        fd.set('disabled',               bv(newOn));
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
        td.classList.remove('dsc-toggling');
        td.classList.add('dsc-clickable');
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
    td.classList.remove('dsc-clickable');
    td.classList.add('dsc-toggling');
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
        td.classList.remove('dsc-toggling');
        td.classList.add('dsc-clickable');
    }
}
</script>'''

    return sb.toString()
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
        tryMethod(dev, "getLabel"),
        tryProperty(dev, "label"),
        tryMethod(dev, "getDisplayName"),
        tryProperty(dev, "displayName"),
        tryMethod(dev, "getName"),
        tryProperty(dev, "name")
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
 * Cell-formatting helpers — shared by buildTableHtml() and buildPrintHtml()
 * -------------------------------------------------------------------------- */

/** Renders the Disabled column value as a coloured Yes/No span. */
private String fmtDisabled(boolean isDisabled) {
    isDisabled ? "<span style='color:red;font-weight:bold;'>Yes</span>"
               : "<span style='color:green;font-weight:bold;'>No</span>"
}

/**
 * Renders Hub Mesh / Command Retry column content.
 * Returns an On/Off span when the feature is available, or a greyed dash when it isn't.
 */
private String fmtToggle(Boolean available, Boolean enabled) {
    if (available != true) return "<span style='color:#bbb;'>—</span>"
    (enabled == true) ? "<span style='color:green;font-weight:bold;'>On</span>"
                      : "<span style='color:#888;'>Off</span>"
}

/**
 * Renders the Logging column for the interactive table — each preference on its own
 * line with colour and a check/cross glyph.
 */
private String fmtLogHtml(List<Map> logSettings) {
    if (!logSettings) return "<span style='color:#bbb;'>—</span>"
    logSettings.collect { Map ls ->
        boolean on = (ls.enabled == true)
        "<span style='font-size:0.88em;${on ? 'color:green;' : 'color:#888;'}'>" +
            "${htmlEscape(safeString(ls.name as String))} ${on ? '✓' : '✗'}</span>"
    }.join('<br>')
}

/**
 * Renders the Logging column for the printable report — plain text, no colour spans.
 */
private String fmtLogPlain(List<Map> logSettings) {
    if (!logSettings) return "—"
    logSettings.collect { Map ls ->
        "${htmlEscape(safeString(ls.name as String))} ${ls.enabled == true ? '✓' : '✗'}"
    }.join('<br>')
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
