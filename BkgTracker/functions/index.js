const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.onExpressSyncRequested = onDocumentWritten(
  "express_sync/{docId}",
  async (event) => {
    const data = event.data.after.data();
    if (!data) return; // document deleted

    let message;

    if (data.action === "stop") {
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
      console.log(`Express sync STOP FCM sent: stoppedBy=${data.stoppedBy}`);
    } else {
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
      console.log(`Express sync FCM sent: requestedBy=${data.requestedBy}, expiresAt=${data.expiresAt}`);
    }

    await getMessaging().send(message);
  }
);
