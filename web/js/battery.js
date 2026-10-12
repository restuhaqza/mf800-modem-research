/* ============================================================
   Battery widget — real percentage, straight from the modem OS.

   Data comes from mifi-helper (`GET :8080/api/battery`), which reads
   Android's BatteryManager — 1% resolution, plus charge state, voltage
   and temperature. The firmware's own funcNo 1001 only reports five
   coarse buckets (`bat_percent` 0-4, see AjaxSevlet.java), which is why
   the old status bar could not show a number.

   That endpoint is deliberately unauthenticated so the always-visible
   header can poll it without asking for the helper PIN. Everything
   degrades silently when :8080 is unreachable (old helper, filtered
   port): the placeholders stay as "--".

   Declare what to show in the markup — no per-page JavaScript:

     <span data-mifi-battery="percent"></span>
         percent | status | voltage | temperature | charging | plugged
     <span data-mifi-battery-box></span>
         gets .charging / .low / .critical / .offline and a <title> tooltip
     <span data-mifi-battery-bar></span>
         width tracks the percentage, same state classes
   ============================================================ */
(function () {
  var ENDPOINT = location.protocol + "//" + location.hostname + ":8080/api/battery";
  var INTERVAL = 20000;
  var TIMEOUT = 8000;

  var FIELDS = {
    percent: function (b) { return ok(b.percent) ? b.percent + "%" : "--"; },
    status: function (b) { return b.status || "--"; },
    voltage: function (b) { return b.voltage_mv > 0 ? (b.voltage_mv / 1000).toFixed(2) + " V" : "--"; },
    temperature: function (b) { return b.temperature_c >= 0 ? b.temperature_c.toFixed(1) + " \u00b0C" : "--"; },
    charging: function (b) { return b.charging ? "Yes" : "No"; },
    plugged: function (b) { return b.plugged && b.plugged !== "none" ? b.plugged.toUpperCase() : "-"; }
  };

  function ok(v) { return typeof v === "number" && isFinite(v) && v >= 0; }

  function summary(b) {
    var parts = [FIELDS.percent(b), b.status || ""];
    if (b.voltage_mv > 0) parts.push(FIELDS.voltage(b));
    if (b.temperature_c >= 0) parts.push(FIELDS.temperature(b));
    parts.push("via " + (b.source || "android"));
    return parts.join(" \u00b7 ");
  }

  function setClass(el, name, on) {
    if (!el || !el.classList) return;
    if (on) el.classList.add(name); else el.classList.remove(name);
  }

  function markOffline(off) {
    var nodes = document.querySelectorAll("[data-mifi-battery-box],[data-mifi-battery-bar]");
    for (var i = 0; i < nodes.length; i++) setClass(nodes[i], "offline", off);
  }

  function paint(b) {
    var i, el, name, nodes;

    nodes = document.querySelectorAll("[data-mifi-battery]");
    for (i = 0; i < nodes.length; i++) {
      el = nodes[i];
      name = el.getAttribute("data-mifi-battery");
      if (FIELDS[name]) el.textContent = FIELDS[name](b);
    }

    var low = ok(b.percent) && b.percent <= 30;
    var pct = ok(b.percent) ? b.percent : 0;

    nodes = document.querySelectorAll("[data-mifi-battery-box],[data-mifi-battery-bar]");
    for (i = 0; i < nodes.length; i++) {
      el = nodes[i];
      setClass(el, "charging", !!b.charging);
      setClass(el, "low", low && b.percent > 15);
      setClass(el, "critical", ok(b.percent) && b.percent <= 15);
      setClass(el, "offline", false);
      el.setAttribute("title", summary(b));
      if (el.hasAttribute("data-mifi-battery-bar")) el.style.width = pct + "%";
    }

    window.mifiBattery = b;   // last reading, for the console / other widgets
  }

  function refresh() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", ENDPOINT + "?_=" + (+new Date()), true);
    xhr.timeout = TIMEOUT;
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var b = null;
      if (xhr.status === 200) {
        try { b = JSON.parse(xhr.responseText); } catch (e) { b = null; }
      }
      if (b && typeof b.percent === "number") paint(b);
      else markOffline(true);   // keep the last known numbers, just dim them
    };
    try { xhr.send(); } catch (e) { markOffline(true); }
  }

  function start() {
    refresh();
    setInterval(refresh, INTERVAL);
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) refresh();
    });
  }

  window.mifiBatteryRefresh = refresh;
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
