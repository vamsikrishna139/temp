# Hotspot Scheduler

Tiny personal Android app (no root). Every hour from 07:00 to 17:00 it turns on Wi-Fi and the
Wi-Fi hotspot, unless the battery is below 20% or Power Saver is on. At 18:00 it turns the hotspot off.
One button on the home screen turns the whole schedule off (and back on).

## Get the APK
Every push builds the app with GitHub Actions. Download `HotspotScheduler.apk` from the
**Releases → latest** page on your phone and install it ("Install anyway" if Play Protect warns that it
was built for an older Android version).

## First launch
1. Allow **Modify system settings** (needed to switch the hotspot).
2. Allow **Don't optimise battery** (so the hourly alarm is not killed).
3. OnePlus/OxygenOS: Settings → Apps → Hotspot Scheduler → Battery → **Allow background activity**
   (and enable auto-launch if your build has it).

## Limits
- Mobile data cannot be switched on without root; the app only logs if it is off.
- If the phone can't run Wi-Fi and hotspot together, Android turns Wi-Fi off when the hotspot starts.
- Each CI build is signed with a fresh debug key: uninstall the old version before installing a new one.
