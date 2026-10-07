# litehub

a home hub for e-waste android hardware (hacked skylight frames, old tablets, cheap wall
panels). it replaces a home assistant webview dashboard with a native app that isn't ass
on 1gb of ram and a slow gpu.

no webview, server, cloud, accounts, anything. :3

## what it do/what's implemented

- dashboards of tiles on a grid, several pages and several dashboards, with themes
- home assistant entities, live state and controls (lights, locks, sensors)
  - more entity types are WIP, haven't gotten around to testing/integrating yet, feel free 
  to submit a PR!
- calendars (ics, webcal, or ha calendar entities) and rss or atom feeds, without needing ha
- weather from open-meteo or an ha weather entity
- a screensaver with a clock, weather and photos (a folder, an immich album, or ha media),
  with night dimming and waking on touch, room light, camera motion or ha entities
- notifications from ha, as banners and a pull down shade
- registers with ha as a companion device, so `notify.mobile_app_<name>` sends to the screen
- plays to the screen as a dlna renderer
- a web editor for layout, themes and settings from another machine on the lan
- backup and restore as a zip, secrets optional and sealed with a passphrase
- updates itself from github releases when asked, through android's own install prompt

## requirements

android 9 or newer. it's built and tested against a skylight calendar (rockchip rk3566,
android 12) and runs on 1gb devices, heavier features turn themselves off there, 
(litweally just the camera/some other fancy shit).

## install

1. grab the apk from [releases](https://github.com/ch4rdotnet/litehub/releases) and install it
2. set litehub as the home app when android asks, so it comes back on boot
3. long press the dashboard for the menu, then settings

over adb that's

```
adb install litehub-v0.1.1.apk
adb shell cmd package set-home-activity com.chardidathing.litehub/.MainActivity
```

## setting it up

everything is in settings (long press the dashboard). the main bits

- **this device** sets a pin (6 digits or more) for the menu and the web editor
- **home assistant** takes the url and a long lived token, then "add to home assistant"
  registers the companion device
- **web** turns on the editor, then open `http://<hub ip>:8080` from a browser on the
  same network. it only answers on the lan
- **backup** saves the whole hub to a zip, and restores one

config can also be dropped in from a computer. put `config.json`, `sources.json` or
`ha.json` in `/sdcard/Android/data/com.chardidathing.litehub/files/` and restart the app,
they're checked and moved in (android 11 and newer).

## building

needs jdk 21 and the android sdk.

```
./gradlew :app:assembleDebug
./gradlew test
```

local builds are versioned `0.0.0-dev` and signed with the debug key, so the updater won't
install a release over one. uninstall first to move to a release build.

## license

gpl 3.0, see [LICENSE](LICENSE).
