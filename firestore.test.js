const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_dev_123";
const BOB_UID = "bob_dev_456";
const ADMIN_UID = "admin_techlive_001";
const ADMIN_EMAIL = "kabirtechlab26@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

function futureDate(offsetMs = 60000) {
  return new Date(Date.now() + offsetMs);
}

test("Unauthenticated users are denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("payments").get());
  await assertFails(unauthDb.collection("system_settings").doc("global").get());
  await assertFails(unauthDb.collection("tutorial_qa").get());
});

test("User profile creation enforces Free plan, USER role, and blocks privilege escalation", async () => {
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true })
    .firestore();

  // Valid creation matching Kotlin UserRepository.createOrSyncProfile
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      name: "Alice Developer",
      email: "alice@example.com",
      role: "USER",
      accountStatus: "ACTIVE",
      plan: "FREE",
      subscriptionStatus: "ACTIVE",
      dailyLiveUsedSeconds: 0,
      dailyUsageDate: "2026-11-20",
      storageUsedBytes: 0,
      storageLimitBytes: 524288000,
      termsAccepted: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Reject self-promotion to ADMIN or LIFETIME
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "ADMIN",
      plan: "LIFETIME",
      updatedAt: pastDate(1000),
    })
  );

  // Allow updating daily usage and name only
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      dailyLiveUsedSeconds: 1200,
      dailyUsageDate: "2026-11-20",
      updatedAt: pastDate(1000),
    })
  );

  // Cross-user PII read denied
  const bobDb = testEnv
    .authenticatedContext(BOB_UID, { email: "bob@example.com", email_verified: true })
    .firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());
});

test("Destinations, Presets, Videos, Streams, and Notifications enforce owner isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("destinations").doc("dest_yt_1").set({
      userId: ALICE_UID,
      name: "Tech Live YouTube",
      platform: "YOUTUBE",
      protocol: "RTMPS",
      rtmpUrl: "rtmps://a.rtmp.youtube.com/live2",
      maskedStreamKey: "••••-9a1b",
      connectionStatus: "CONNECTED",
      maxResolution: "2160p",
      maxFps: 60,
      supports60Fps: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_1").set({
      userId: ALICE_UID,
      name: "Gaming 1080p",
      resolution: "1080p",
      fps: 60,
      bitrateKbps: 6000,
      audioBitrateKbps: 160,
      orientation: "LANDSCAPE_16_9",
      aspectFitMode: "FIT",
      destinationNames: ["YouTube"],
      microphoneEnabled: true,
      cameraEnabled: true,
      encoderProfile: "H264_HIGH",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_1").set({
      userId: ALICE_UID,
      title: "Compose Architecture Deep Dive",
      fileName: "compose_tutorial.mp4",
      mimeType: "video/mp4",
      fileSizeBytes: 245000000,
      durationSeconds: 1840,
      resolution: "1920x1080",
      fps: 60,
      codec: "h264",
      audioCodec: "aac",
      status: "READY",
      uploadProgress: 100,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("streams").doc("stream_1").set({
      userId: ALICE_UID,
      title: "Kotlin Coroutines Live Workshop",
      sourceType: "CAMERA",
      destinations: ["YouTube"],
      orientation: "LANDSCAPE_16_9",
      aspectFitMode: "FIT",
      resolution: "1080p",
      fps: 60,
      bitrateKbps: 6400,
      audioBitrateKbps: 160,
      status: "RUNNING",
      cloudStream: false,
      durationLimitSeconds: 10800,
      loopMode: "ONCE",
      elapsedSeconds: 320,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Reject future timestamp tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("notifications").doc("notif_bad").set({
      userId: ALICE_UID,
      title: "Tampered",
      message: "Future timestamp",
      category: "STREAM",
      read: false,
      createdAt: futureDate(),
    })
  );

  // Bob cannot list or read Alice's subcollections
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("destinations").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("streams").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("videos").get());
});

test("Payments cannot be self-approved by users; only verified Admin can verify payments", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // User cannot create a pre-approved PAID payment
  await assertFails(
    aliceDb.collection("payments").doc("pay_cheat").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      userName: "Alice",
      plan: "LIFETIME",
      amountBdt: 1500,
      currency: "BDT",
      paymentMethod: "BKASH",
      transactionReference: "TRX99887766",
      gatewayMode: "MANUAL",
      status: "PAID",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Valid PENDING manual payment submission succeeds
  await assertSucceeds(
    aliceDb.collection("payments").doc("pay_valid_1").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      userName: "Alice",
      plan: "LIFETIME",
      amountBdt: 1500,
      currency: "BDT",
      paymentMethod: "BKASH",
      transactionReference: "TRX99887766",
      gatewayMode: "MANUAL",
      status: "PENDING",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Filtered query succeeds, unfiltered list fails for normal user
  await assertSucceeds(aliceDb.collection("payments").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDb.collection("payments").get());

  // User cannot approve own payment
  await assertFails(
    aliceDb.collection("payments").doc("pay_valid_1").update({
      status: "PAID",
      updatedAt: pastDate(1000),
    })
  );

  // Spoofed unverified admin email is rejected
  const spoofedAdminDb = testEnv
    .authenticatedContext("spoof_uid", { email: ADMIN_EMAIL, email_verified: false })
    .firestore();
  await assertFails(
    spoofedAdminDb.collection("payments").doc("pay_valid_1").update({
      status: "PAID",
      verifiedBy: "spoof_uid",
      verifiedAt: pastDate(1000),
      updatedAt: pastDate(1000),
    })
  );

  // Verified admin succeeds
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  await assertSucceeds(
    adminDb.collection("payments").doc("pay_valid_1").update({
      status: "PAID",
      adminNote: "Verified bKash TRX99887766",
      verifiedBy: ADMIN_UID,
      verifiedAt: pastDate(1000),
      updatedAt: pastDate(1000),
    })
  );
});

test("Community Q&A allows signed-in read/create/upvote while protecting author ownership", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("tutorial_qa").doc("qa_1").set({
      userId: ALICE_UID,
      authorName: "Alice",
      topicTag: "Jetpack Compose",
      questionText: "How do you avoid recomposition during 60 FPS camera overlay rendering?",
      upvotes: 0,
      answered: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(bobDb.collection("tutorial_qa").get());
  await assertSucceeds(
    bobDb.collection("tutorial_qa").doc("qa_1").update({
      upvotes: 1,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(bobDb.collection("tutorial_qa").doc("qa_1").delete());
});
