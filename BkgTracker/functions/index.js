const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

// Family allowlist — keep in sync with isFamily() in firestore.rules.
const FAMILY_EMAILS = [
  "smjoshi@gmail.com",
  "bioconcepts.pune@gmail.com",
  "saee1175@gmail.com",
  "gjoshi1208@gmail.com",
];

// Minimum gap between broadcasts from the SAME doc. Repeated writes within this
// window are ignored (debounce) to prevent battery-drain abuse. Zero extra cost:
// uses the event's `before` snapshot, no Firestore read.
const THROTTLE_MS = 30_000;

// Max allowed express duration (1h + small clock-skew buffer), matching firestore.rules.
const MAX_EXPIRES_AHEAD_MS = 3_700_000;

exports.onExpressSyncRequested = onDocumentWritten(
  "express_sync/{docId}",
  async (event) => {
    const data = event.data.after.data();
    if (!data) return; // document deleted

    const before = event.data.before.exists ? event.data.before.data() : null;
    const now = Date.now();

    let message;

    if (data.action === "stop") {
      // Validate stopper is a known family member.
      if (!FAMILY_EMAILS.includes(data.stoppedBy)) {
        console.warn(`Rejected STOP: stoppedBy not in family allowlist: ${data.stoppedBy}`);
        return;
      }
      // Throttle: ignore repeated stops within THROTTLE_MS on the same doc.
      if (before && before.action === "stop" &&
          typeof before.stoppedAt === "number" &&
          typeof data.stoppedAt === "number" &&
          data.stoppedAt - before.stoppedAt < THROTTLE_MS) {
        console.log(`Throttled STOP: stoppedBy=${data.stoppedBy} (within ${THROTTLE_MS}ms)`);
        return;
      }
      message = {
        topic: "bkgtracker_family",
        data: {
          type: "express_sync_stop",
          stoppedBy: data.stoppedBy || "",
        },
        android: {
          priority: "high",
        },
      };
      console.log("Express sync STOP FCM sent");
    } else {
      // START request — validate requester and expiry window.
      if (!FAMILY_EMAILS.includes(data.requestedBy)) {
        console.warn(`Rejected START: requestedBy not in family allowlist: ${data.requestedBy}`);
        return;
      }
      if (typeof data.expiresAt !== "number" ||
          data.expiresAt <= now ||
          data.expiresAt > now + MAX_EXPIRES_AHEAD_MS) {
        console.warn(`Rejected START: invalid expiresAt=${data.expiresAt}`);
        return;
      }
      // Throttle: ignore repeated starts within THROTTLE_MS on the same doc.
      if (before && before.action !== "stop" &&
          typeof before.requestedAt === "number" &&
          typeof data.requestedAt === "number" &&
          data.requestedAt - before.requestedAt < THROTTLE_MS) {
        console.log(`Throttled START: requestedBy=${data.requestedBy} (within ${THROTTLE_MS}ms)`);
        return;
      }
      message = {
        topic: "bkgtracker_family",
        data: {
          type: "express_sync",
          expiresAt: String(data.expiresAt),
          requestedBy: data.requestedBy || "",
        },
        android: {
          priority: "high",
        },
      };
      console.log("Express sync START FCM sent");
    }

    await getMessaging().send(message);
  }
);
