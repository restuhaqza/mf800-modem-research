var API = "http://" + location.hostname + ":8080";

(function () {
  var css = document.createElement("style");
  css.textContent =
    "#mifiAuth{position:fixed;left:0;top:0;right:0;bottom:0;background:rgba(38,50,31,.5);" +
    "display:flex;align-items:center;justify-content:center;z-index:99999}" +
    ".mifi-auth-card{background:#fff;border-radius:14px;box-shadow:0 12px 40px rgba(30,50,15,.3);" +
    "padding:26px 26px 22px;width:min(320px,86vw);font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif}" +
    ".mifi-auth-card h3{margin:0 0 14px;font-size:15px;color:#3a5a1e}" +
    ".mifi-auth-card input{width:100%;box-sizing:border-box;padding:11px 12px;font-size:16px;" +
    "border:1px solid #dfe8d3;border-radius:8px;outline:none}" +
    ".mifi-auth-card input:focus{border-color:#8dc63f}" +
    ".mifi-auth-err{color:#e74c3c;font-size:12px;min-height:16px;margin:8px 0 2px}" +
    ".mifi-auth-card button{width:100%;margin-top:8px;padding:11px;border:0;border-radius:8px;" +
    "background:linear-gradient(135deg,#8dc63f,#5a9e1f);color:#fff;font-size:14px;font-weight:600;cursor:pointer}" +
    ".mifi-auth-hint{font-size:11px;color:#9aa68f;margin-top:10px;text-align:center}";
  document.head.appendChild(css);
})();

function mifiPin() {
  try { return localStorage.getItem("mifi_pin") || ""; } catch (e) { return ""; }
}

function mifiApplyPin(pin) {
  try { localStorage.setItem("mifi_pin", pin); } catch (e) {}
  if (window.jQuery) jQuery.ajaxSetup({ headers: { "X-Auth": pin } });
}

function mifiAuthOverlay(onOk) {
  if (document.getElementById("mifiAuth")) return;
  var d = document.createElement("div");
  d.id = "mifiAuth";
  d.innerHTML =
    '<div class="mifi-auth-card"><h3>&#128274; PIN mifi-helper</h3>' +
    '<input type="password" id="mifiPinInput" placeholder="PIN" autocomplete="off">' +
    '<div id="mifiAuthErr" class="mifi-auth-err"></div>' +
    '<button id="mifiAuthGo" type="button">Buka</button>' +
    '<div class="mifi-auth-hint">PIN default: admin &mdash; segera ganti via<br><code>POST /api/auth/pin</code></div></div>';
  document.body.appendChild(d);
  function tryPin() {
    var pin = document.getElementById("mifiPinInput").value;
    var err = document.getElementById("mifiAuthErr");
    if (!pin) { err.textContent = "Isi PIN dulu."; return; }
    jQuery.post(API + "/api/auth/login", { pin: pin })
      .done(function () {
        mifiApplyPin(pin);
        d.parentNode.removeChild(d);
        if (onOk) onOk();
      })
      .fail(function (xhr) {
        err.textContent = xhr.status === 429
          ? "Terkunci ~5 menit (terlalu banyak PIN salah)."
          : "PIN salah.";
      });
  }
  document.getElementById("mifiAuthGo").onclick = tryPin;
  document.getElementById("mifiPinInput").onkeydown = function (e) {
    if (e.which === 13) tryPin();
  };
  setTimeout(function () {
    var i = document.getElementById("mifiPinInput");
    if (i) i.focus();
  }, 50);
}

function mifiAuthStart(onOk) {
  var pin = mifiPin();
  if (!pin) { mifiAuthOverlay(onOk); return; }
  jQuery.post(API + "/api/auth/login", { pin: pin })
    .done(function () {
      mifiApplyPin(pin);
      if (onOk) onOk();
    })
    .fail(function () {
      mifiAuthOverlay(onOk);
    });
}
