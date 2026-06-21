/**
 * sync.js — Bella-Bona daily sync: MySQL orders → Firestore
 *
 * Runs via GitHub Actions every weekday at 9:00 AM Berlin time.
 * Reads confirmed orders from AWS RDS → writes to Firestore.
 * Frontend reads Firestore directly — NO backend server needed.
 *
 * Usage:  node sync.js [YYYY-MM-DD]
 *         (defaults to tomorrow if no date given)
 */

const { initializeApp, cert } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const mysql = require('mysql2/promise');

// ─── Init Firebase Admin ──────────────────────────────────────────────────────
const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
initializeApp({ credential: cert(serviceAccount) });
const db = getFirestore();

// ─── MySQL connection ─────────────────────────────────────────────────────────
const pool = mysql.createPool({
  host:     process.env.MYSQL_HOST,
  port:     process.env.MYSQL_PORT || 3306,
  user:     process.env.MYSQL_USER,
  password: process.env.MYSQL_PASSWORD,
  database: process.env.MYSQL_DATABASE || 'bellabona',
  ssl:      { rejectUnauthorized: false },
  connectionLimit: 3,
});

const KITCHEN_IDS = {
  Munich: 'd9b6c7d3-869f-454e-99d6-96e1930531da',
  Berlin: '069b0022-7a83-4abe-9b53-a6c39c276199',
};

async function syncOrdersForDate(date, kitchen) {
  const kitchenId = KITCHEN_IDS[kitchen];
  console.log(`\n🔄 Syncing ${kitchen} orders for ${date}...`);

  const [stops] = await pool.query(`
    SELECT
      b.id           AS branchId,
      b.name         AS company,
      b.address,
      TRIM(b.postCode) AS postCode,
      b.city,
      b.deliveryInfo,
      COUNT(o.id)    AS orderCount
    FROM orders o
    JOIN branch b ON o.branchId = b.id
    WHERE o.deliveryDate = ? AND o.status != 'cancelled' AND b.kitchenId = ?
    GROUP BY b.id, b.name, b.address, b.postCode, b.city, b.deliveryInfo
    ORDER BY b.postCode, b.name
  `, [date, kitchenId]);

  console.log(`   Found ${stops.length} stops, ${stops.reduce((s,r)=>s+r.orderCount,0)} orders`);

  if (stops.length === 0) {
    console.log(`   ⚠️  No orders found — skipping`);
    return;
  }

  // Write to Firestore in batches of 500
  const batchSize = 400;
  let batchCount = 0;

  for (let i = 0; i < stops.length; i += batchSize) {
    const batch = db.batch();
    const chunk = stops.slice(i, i + batchSize);

    chunk.forEach(s => {
      const docRef = db.collection('orders')
        .doc(`${date}-${kitchen}`)
        .collection('stops')
        .doc(s.branchId);

      batch.set(docRef, {
        branchId:    s.branchId,
        company:     s.company,
        address:     s.address,
        postCode:    s.postCode,
        city:        s.city,
        deliveryInfo: s.deliveryInfo || null,
        orderCount:  s.orderCount,
        weightKg:    s.orderCount * 14,
        kitchen,
        deliveryDate: date,
        syncedAt:    Timestamp.now(),
      });
    });

    await batch.commit();
    batchCount++;
  }

  // Write summary doc for quick lookups
  await db.collection('orders').doc(`${date}-${kitchen}`).set({
    date, kitchen, kitchenId,
    totalStops:  stops.length,
    totalOrders: stops.reduce((s, r) => s + r.orderCount, 0),
    syncedAt:    Timestamp.now(),
  }, { merge: true });

  console.log(`   ✅ Synced ${stops.length} stops in ${batchCount} batch(es)`);
}

async function syncAnalytics(days = 30) {
  console.log(`\n📊 Syncing analytics (last ${days} days)...`);
  const since = new Date();
  since.setDate(since.getDate() - days);
  const sinceStr = since.toISOString().split('T')[0];

  // Daily totals
  const [rows] = await pool.query(`
    SELECT
      DATE(t.deliveryDate) as date,
      b.kitchenId,
      k.name as kitchen,
      COUNT(t.id) as total,
      SUM(CASE WHEN t.status='success' THEN 1 ELSE 0 END) as succeeded,
      SUM(CASE WHEN t.status='cancelled' THEN 1 ELSE 0 END) as cancelled,
      SUM(CASE WHEN
        t.status='success'
        AND TIMESTAMPDIFF(MINUTE,
          JSON_UNQUOTE(JSON_EXTRACT(t.additionalData,'$.delivery.before')),
          JSON_UNQUOTE(JSON_EXTRACT(t.additionalData,'$.delivery.eta'))
        ) <= COALESCE(JSON_EXTRACT(t.additionalData,'$.delivery.allowed_delay'),5)
      THEN 1 ELSE 0 END) as on_time
    FROM tiramizoo_log t
    JOIN branch b ON b.id = t.branchId
    JOIN kitchen k ON k.id = b.kitchenId
    WHERE t.deliveryDate >= ?
    GROUP BY DATE(t.deliveryDate), b.kitchenId, k.name
    ORDER BY date DESC
  `, [sinceStr]);

  const batch = db.batch();
  rows.forEach(r => {
    const d = typeof r.date === 'string' ? r.date : new Date(r.date).toISOString().split('T')[0];
    batch.set(db.collection('analytics_daily').doc(`${d}-${r.kitchen}`), {
      date: d, kitchen: r.kitchen,
      total: r.total, succeeded: r.succeeded,
      cancelled: r.cancelled, on_time: r.on_time,
      onTimeRate: r.succeeded > 0 ? Math.round(r.on_time/r.succeeded*100) : null,
      syncedAt: Timestamp.now(),
    });
  });
  await batch.commit();

  // Courier performance (last 30 days)
  const [courierRows] = await pool.query(`
    SELECT
      JSON_UNQUOTE(JSON_EXTRACT(t.additionalData,'$.courierName')) as courierName,
      k.name as kitchen,
      COUNT(*) as total,
      SUM(CASE WHEN t.status='success' THEN 1 ELSE 0 END) as succeeded,
      SUM(CASE WHEN t.status='cancelled' THEN 1 ELSE 0 END) as cancelled,
      SUM(CASE WHEN
        t.status='success'
        AND TIMESTAMPDIFF(MINUTE,
          JSON_UNQUOTE(JSON_EXTRACT(t.additionalData,'$.delivery.before')),
          JSON_UNQUOTE(JSON_EXTRACT(t.additionalData,'$.delivery.eta'))
        ) <= COALESCE(JSON_EXTRACT(t.additionalData,'$.delivery.allowed_delay'),5)
      THEN 1 ELSE 0 END) as on_time,
      COUNT(DISTINCT t.deliveryDate) as active_days
    FROM tiramizoo_log t
    JOIN branch b ON b.id=t.branchId
    JOIN kitchen k ON k.id=b.kitchenId
    WHERE t.deliveryDate >= ?
      AND JSON_EXTRACT(t.additionalData,'$.courierName') IS NOT NULL
    GROUP BY courierName, k.name
    HAVING total >= 3
    ORDER BY total DESC
  `, [sinceStr]);

  const cb = db.batch();
  courierRows.forEach(r => {
    if (!r.courierName) return;
    cb.set(db.collection('analytics_couriers').doc(`${r.courierName}-${r.kitchen}`), {
      courierName: r.courierName, kitchen: r.kitchen,
      total: r.total, succeeded: r.succeeded, cancelled: r.cancelled,
      onTime: r.on_time,
      onTimeRate: r.succeeded > 0 ? Math.round(r.on_time/r.succeeded*100) : null,
      activeDays: r.active_days,
      avgPerDay: r.active_days > 0 ? Math.round(r.total/r.active_days) : 0,
      syncedAt: Timestamp.now(),
    });
  });
  await cb.commit();

  console.log(`   ✅ Analytics: ${rows.length} daily records, ${courierRows.length} couriers`);
}

async function main() {
  // Date arg or tomorrow
  const dateArg = process.argv[2];
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const date = dateArg || tomorrow.toISOString().split('T')[0];

  console.log('🚀 Bella-Bona Sync — MySQL → Firestore');
  console.log(`   Target date: ${date}`);
  console.log(`   Project:     routing-22aad`);

  try {
    // Sync orders for both kitchens
    await syncOrdersForDate(date, 'Berlin');
    await syncOrdersForDate(date, 'Munich');

    // Also sync today's orders (in case they changed)
    const today = new Date().toISOString().split('T')[0];
    if (today !== date) {
      await syncOrdersForDate(today, 'Berlin');
      await syncOrdersForDate(today, 'Munich');
    }

    // Sync analytics
    await syncAnalytics(30);

    console.log('\n✅ Sync complete!');
    console.log('   Dashboard reads from Firestore — no backend needed.');
  } catch (err) {
    console.error('\n❌ Sync failed:', err.message);
    process.exit(1);
  } finally {
    await pool.end();
    process.exit(0);
  }
}

main();
