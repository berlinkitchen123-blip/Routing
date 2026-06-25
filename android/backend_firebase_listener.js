// ============================================================
// ADD THIS BLOCK TO YOUR backend index.html (inside the main <script type="module">)
// Place it after Firebase is initialized (after the existing firebaseConfig block)
// ============================================================

// ── Real-time: Android app delivery status → backend planning grid ──────────
function initAndroidAppSync() {
  const { getFirestore, collection, onSnapshot, doc, updateDoc } =
    window._firestoreModules || {};         // use your existing Firestore imports

  if (!getFirestore) {
    console.warn('[AppSync] Firestore modules not found, skipping Android sync');
    return;
  }

  const db = getFirestore();

  // 1. Listen to delivery status changes from Android app
  onSnapshot(collection(db, 'deliveries'), (snapshot) => {
    snapshot.docChanges().forEach((change) => {
      if (change.type === 'modified' || change.type === 'added') {
        const task = change.doc.data();
        const taskId = change.doc.id;

        if (task.status === 'DELIVERED' || task.status === 'PICKED_UP') {
          _onDeliveryStatusChanged(taskId, task);
        }
      }
    });
  });

  // 2. Listen to driver GPS locations → show on map
  onSnapshot(collection(db, 'driver_locations'), (snapshot) => {
    snapshot.docChanges().forEach((change) => {
      const loc = change.doc.data();
      if (loc && loc.lat && loc.lng) {
        _updateDriverMarker(loc.driverId, loc.lat, loc.lng, loc.online);
      }
    });
  });

  // 3. Listen to driver events (box checks, scans, navigation)
  const today = new Date().toISOString().slice(0, 10); // yyyy-mm-dd
  onSnapshot(collection(db, 'driver_events', today), (snapshot) => {
    snapshot.docChanges().forEach((change) => {
      if (change.type === 'added') {
        const event = change.doc.data();
        _onDriverEvent(event);
      }
    });
  });

  console.log('[AppSync] Android ↔ Backend real-time sync active');
}

// Called when Android app marks a delivery delivered/picked up
function _onDeliveryStatusChanged(taskId, task) {
  const status = task.status;           // "DELIVERED" or "PICKED_UP"
  const driverName = task.driverId || '';
  const companyName = task.companyName || taskId;

  console.log(`[AppSync] ${driverName} → ${status}: ${companyName}`);

  // Update the planning grid cell if it's visible
  const gridCells = document.querySelectorAll(`[data-task-id="${taskId}"], [data-stop-id="${taskId}"]`);
  gridCells.forEach(cell => {
    cell.style.backgroundColor = status === 'DELIVERED' ? '#d4edda' : '#fff3cd';
    cell.title = `${status} by ${driverName} at ${new Date(task.proofTimestamp || Date.now()).toLocaleTimeString('de-DE')}`;
  });

  // Flash a toast notification on the backend
  _showSyncToast(`✅ ${driverName}: ${companyName} → ${status}`);

  // Re-render dispatch view if it's open (Drive already has renderDispatch)
  if (typeof renderDispatch === 'function') {
    renderDispatch();
  }
}

// Called when GPS update arrives from a driver's Android app
const _driverMarkers = {};
function _updateDriverMarker(driverId, lat, lng, online) {
  if (!window._dashMap) return;   // map not initialised yet

  if (!_driverMarkers[driverId]) {
    _driverMarkers[driverId] = L.marker([lat, lng], {
      icon: L.divIcon({
        className: '',
        html: `<div style="background:#1A73E8;color:#fff;padding:2px 6px;border-radius:12px;font-size:11px;white-space:nowrap">${driverId}</div>`,
        iconAnchor: [0, 0]
      })
    }).addTo(window._dashMap).bindPopup(driverId);
  } else {
    _driverMarkers[driverId].setLatLng([lat, lng]);
  }

  if (!online) {
    _driverMarkers[driverId].setOpacity(0.4);
  }
}

// Called on every driver event (box checks, scans, navigation)
function _onDriverEvent(event) {
  const { driverId, action, taskId, companyName } = event;
  // Only log notable events — not every heartbeat
  const notable = ['QR_SCANNED', 'QR_MATCHED', 'DELIVERED', 'BOX_CHECKED', 'NAVIGATE_STARTED'];
  if (notable.includes(action)) {
    console.log(`[AppSync] Event: ${driverId} → ${action} (${companyName || taskId})`);
  }
}

// Small toast notification (reuse existing if Drive has one, otherwise fallback)
function _showSyncToast(msg) {
  if (typeof openModal === 'function') return; // don't override modals
  const el = document.createElement('div');
  el.textContent = msg;
  el.style.cssText = 'position:fixed;bottom:24px;right:24px;background:#333;color:#fff;padding:10px 18px;border-radius:8px;z-index:9999;font-size:14px;opacity:0;transition:opacity .3s';
  document.body.appendChild(el);
  requestAnimationFrame(() => { el.style.opacity = '1'; });
  setTimeout(() => {
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 400);
  }, 3500);
}

// ── planSaveToApp: write route to Firestore so Android app can read it ────────
// REPLACE your existing planSaveToApp function with this one (or merge it in)
async function planSaveToApp_withFirestore(date, city) {
  const { getFirestore, collection, doc, setDoc, writeBatch } =
    window._firestoreModules || {};

  if (!getFirestore) {
    console.error('[planSaveToApp] Firestore not available');
    return;
  }

  const db = getFirestore();
  const plan = window._currentPlan || {};   // your existing plan data structure
  const dateStr = date || new Date().toLocaleDateString('de-DE'); // dd.MM.yyyy

  const batch = writeBatch(db);
  let count = 0;

  Object.entries(plan).forEach(([driverName, stops]) => {
    if (!Array.isArray(stops)) return;

    stops.forEach((stop, index) => {
      if (!stop || stop.type === 'pickup_base') return;

      const docRef = doc(collection(db, 'deliveries'));
      batch.set(docRef, {
        driverId: driverName,
        companyName: stop.name || stop.company || '',
        address: stop.address || '',
        postalCode: stop.postalCode || stop.zip || '',
        deliveryInstructions: stop.notes || stop.requirements || '',
        numberOfBoxes: stop.boxes || stop.packages || 1,
        status: 'PENDING',
        date: dateStr,
        routeOrder: index,
        order: index,
        sequence: index,
        time: stop.time || stop.window || '',
        qrCode: stop.orderId || stop.id || `${driverName}_${index}_${dateStr}`,
        timestamp: new Date().toISOString()
      });
      count++;
    });
  });

  await batch.commit();
  console.log(`[planSaveToApp] Wrote ${count} deliveries to Firestore for ${dateStr}`);
  alert(`✅ Saved ${count} stops to Driver App for ${dateStr}`);
}

// ── Bootstrap ────────────────────────────────────────────────────────────────
// Call this once after Firebase is ready (e.g., after your existing initFirebase())
// initAndroidAppSync();
