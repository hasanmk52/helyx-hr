/*
 * Global htmx glue (UI Guidelines §13: no inline JavaScript in templates).
 *
 * Three responsibilities, the first applying site-wide and the other two specific to the
 * Divisions/Departments admin page (Phase 1.3) but written generically enough to serve any
 * future offcanvas-based admin screen:
 *   0. Attaching the CSRF header to every htmx state-changing request. Spring Security's
 *      Thymeleaf dialect only auto-injects a hidden CSRF field into forms it decorates via
 *      th:action; these forms submit through hx-post/hx-patch/hx-delete instead, so nothing
 *      would carry the token without this — see fragments/head.html's meta tags.
 *   1. Opening an offcanvas after htmx swaps a form into its body (Add/Edit both work this way —
 *      see AdminOrganizationController's Javadoc for why the offcanvas element itself is never
 *      part of an htmx response).
 *   2. Showing a toast and closing any open offcanvas when the server signals success via the
 *      HX-Trigger response header (UI Guidelines §7.4).
 */
(function () {
  "use strict";

  document.body.addEventListener("htmx:configRequest", function (event) {
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) {
      event.detail.headers[header.content] = token.content;
    }
  });

  var OFFCANVAS_BODY_IDS = [
    "divisionOffcanvasBody",
    "departmentOffcanvasBody",
    "employeeOffcanvasBody",
    "leaveTypeOffcanvasBody",
    "holidayOffcanvasBody",
  ];

  document.body.addEventListener("htmx:afterSwap", function (event) {
    if (OFFCANVAS_BODY_IDS.indexOf(event.detail.target.id) === -1) {
      return;
    }
    // Only GET (new-form/edit-form) opens the panel. A POST/PATCH swap into the same target
    // is a save response resetting the form to blank — showing here would fight the
    // organization-toast handler's hide() on the very same round trip.
    if (event.detail.requestConfig.verb !== "get") {
      return;
    }
    var offcanvasEl = event.detail.target.closest(".offcanvas");
    if (offcanvasEl) {
      bootstrap.Offcanvas.getOrCreateInstance(offcanvasEl).show();
    }
  });

  document.body.addEventListener("organization-toast", function (event) {
    OFFCANVAS_BODY_IDS.forEach(function (id) {
      var body = document.getElementById(id);
      var offcanvasEl = body && body.closest(".offcanvas");
      var instance = offcanvasEl && bootstrap.Offcanvas.getInstance(offcanvasEl);
      if (instance) {
        instance.hide();
      }
    });
    showToast(event.detail.message);
  });

  function showToast(message) {
    var container = document.getElementById("toast-container");
    if (!container) {
      return;
    }
    var toastEl = document.createElement("div");
    toastEl.className = "toast align-items-center text-bg-success border-0";
    toastEl.setAttribute("role", "status");
    toastEl.setAttribute("aria-live", "polite");
    toastEl.setAttribute("aria-atomic", "true");

    var flex = document.createElement("div");
    flex.className = "d-flex";

    var body = document.createElement("div");
    body.className = "toast-body";
    body.textContent = message;

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "btn-close btn-close-white me-2 m-auto";
    closeButton.setAttribute("data-bs-dismiss", "toast");
    closeButton.setAttribute("aria-label", "Close");

    flex.appendChild(body);
    flex.appendChild(closeButton);
    toastEl.appendChild(flex);
    container.appendChild(toastEl);

    var toast = new bootstrap.Toast(toastEl, { delay: 4000 });
    toastEl.addEventListener("hidden.bs.toast", function () {
      toastEl.remove();
    });
    toast.show();
  }
})();
