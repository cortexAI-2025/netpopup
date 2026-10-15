/**
 * NetPopUp Cloud Functions.
 *
 * notifyOnMessage — on every new /messages/{id} document, publish a data-only
 * FCM message to the room topic "room_<roomId>". The Android client
 * (NetPopUpMessagingService) subscribes to the topics of its local zone and of
 * the private rooms it joined, and filters out its own messages, blocked users
 * and the room currently on screen.
 *
 * Data-only (no "notification" block) on purpose: otherwise Android would show
 * the notification by itself while the app is in background, bypassing that
 * client-side filtering.
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

/** Must match PushRepository.topicFor() on Android. */
const topicFor = (roomId) => `room_${roomId}`;

/** Keeps the notification short; the full text is in the app. */
const MAX_BODY = 140;

exports.notifyOnMessage = onDocumentCreated("messages/{messageId}", async (event) => {
  const message = event.data && event.data.data();
  if (!message || !message.roomId || !message.content) return;

  const roomSnap = await getFirestore().collection("rooms").doc(message.roomId).get();
  const roomType = roomSnap.exists ? roomSnap.get("type") || "LOCAL" : "LOCAL";

  const body = message.content.length > MAX_BODY
    ? `${message.content.slice(0, MAX_BODY - 1)}…`
    : message.content;

  try {
    await getMessaging().send({
      topic: topicFor(message.roomId),
      data: {
        roomId: String(message.roomId),
        roomType: String(roomType),
        senderId: String(message.userId || ""),
        title: String(message.username || "NetPopUp"),
        body,
      },
      android: {
        priority: "high",
        // A chat notification is worthless once the message has expired
        ttl: 60 * 60 * 1000,
      },
    });
  } catch (err) {
    logger.error("FCM send failed", { roomId: message.roomId, err: err.message });
  }
});
