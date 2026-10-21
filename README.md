# Hotspot Scheduler

Tiny personal Android app (no root, uses [Shizuku](https://shizuku.rikka.app/)). Every hour from 07:00 to
17:00 it turns on Wi-Fi, mobile data and the Wi-Fi hotspot, unless the battery is below 20% or Power Saver
is on. At 18:00 it turns the hotspot off. One button on the home screen turns the whole schedule off
(and back on).

## Get the APK
Every push builds the app with GitHub Actions. Download `HotspotScheduler.apk` from
**Releases → latest** on your phone and install it.

## One-time setup
1. Settings → About phone → tap **Build number** 7 times (enables Developer options).
2. Install **Shizuku** from the Play Store, open it, and start it via **Wireless debugging** (follow its steps).
3. Open Hotspot Scheduler, allow it in the Shizuku prompt, and allow **Don't optimise battery**.
4. OnePlus/OxygenOS: Settings → Apps → Hotspot Scheduler → Battery → **Allow background activity**.

**After every phone restart:** open Shizuku and tap **Start** again. Until then the schedule only logs
"Shizuku not running".

## Notes
- Order each hour: mobile data, then hotspot, then Wi-Fi. Android switches Wi-Fi off while the hotspot
  starts, so Wi-Fi is turned back on afterwards (works on phones that support Wi-Fi + hotspot together).
- Each CI build is signed with a fresh debug key: uninstall the old version before installing a new one.
