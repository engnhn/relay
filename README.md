# relay

relay forwards android notifications to a linux desktop over the local network. the android app discovers the receiver with mdns, pins the receiver's tls identity, listens to real notification events through android's native `NotificationListenerService`, and sends each event to the linux receiver over secure websocket. the receiver displays the event through the standard freedesktop notification system.

v0.2.0 is intentionally local-only. it does not use a backend, cloud service, account, database, docker, tray app, clipboard sync, file transfer, notification actions, or ios support.

## layout

```text
receiver/  rust cli websocket receiver for linux
mobile/    flutter android app with native kotlin notification forwarding
```

## quick start

start the linux receiver:

```sh
cd receiver
RELAY_TOKEN=change-me cargo run -- listen
```

the receiver prints a `receiver identity fingerprint`. copy that fingerprint to the android app once. mdns discovery is only used to find the receiver address; android still rejects any receiver whose tls certificate fingerprint does not match the saved value.

build the android debug apk:

```sh
cd mobile
flutter build apk --debug
```

install with adb:

```sh
adb install -r mobile/build/app/outputs/flutter-apk/app-debug.apk
```

open relay on the phone. if mdns discovery is available on the network, relay fills the linux machine address automatically. enter the same token and the receiver fingerprint, then tap `connect`. after that, relay can reconnect to the same receiver identity when both devices are on the same network.

## receiver

the receiver binds to `0.0.0.0:9876` by default so a phone on the same local network can reach it. it creates a persistent self-signed tls identity under `~/.config/relay` on first run.

```sh
cd receiver
cargo run -- listen
```

custom port:

```sh
cargo run -- listen --port 9999
```

custom bind address:

```sh
cargo run -- listen --host 0.0.0.0 --port 9876
```

shared token:

```sh
cargo run -- listen --token change-me
```

local testing without a token:

```sh
cargo run -- listen --allow-empty-token
```

custom identity directory:

```sh
cargo run -- listen --identity-dir ~/.config/relay
```

environment configuration:

```sh
RELAY_HOST=0.0.0.0 RELAY_PORT=9876 RELAY_TOKEN=change-me cargo run -- listen
```

runtime behavior:

| behavior | detail |
| --- | --- |
| protocol | secure websocket over tls |
| event format | json notification payloads |
| default bind | `0.0.0.0:9876` |
| server identity | persistent self-signed certificate pinned by SHA-256 fingerprint |
| client authentication | required shared token through `--token` or `RELAY_TOKEN` |
| discovery | mdns/dns-sd service advertisement as `_relay._tcp` |
| shutdown | clean `Ctrl+C` handling |
| failures | malformed messages and disconnects are logged without stopping the server |
| output | linux desktop notifications through freedesktop |

## android

the flutter screen is only configuration and status. notification capture and websocket sending live in the native kotlin layer so forwarding can continue from the android notification listener using the saved connection.

the android client discovers `_relay._tcp` services on the current network, stores the host, port, token, and pinned receiver fingerprint locally. if the secure websocket drops, relay retries with exponential backoff up to 30 seconds and keeps the latest pending notification payloads in memory.

background behavior:

| behavior | detail |
| --- | --- |
| app screen closed | notification listener can continue forwarding posted notifications |
| receiver ip changed | relay tries mdns discovery in the background before reconnecting |
| phone process restarted | pending notification payloads are restored from local storage |
| phone rebooted | relay prepares the saved connection after boot when android allows the boot receiver to run |
| force stop | android blocks background work until relay is opened again |
| battery restrictions | aggressive vendor battery rules can still stop background delivery |

grant notification access:

1. open relay on the phone.
2. tap `open notification access settings`.
3. choose `relay`.
4. allow notification access.

relay ignores notifications posted by itself.

## network

find the linux machine's local ip address:

```sh
hostname -I
```

use the address on the same network as the phone, usually `192.168.x.x` or `10.x.x.x`. ignore docker-style addresses such as `172.x.x.x` unless the phone is actually on that network.

if usb installation is not available, serve the apk from the linux machine:

```sh
cd mobile/build/app/outputs/flutter-apk
python3 -m http.server 8000 --bind 0.0.0.0
```

then open this URL on the phone:

```text
http://<linux-ip>:8000/app-debug.apk
```

android may ask for permission to install unknown apps from the browser.

## event payload

android sends one json message per posted notification:

```json
{
  "type": "notification.created",
  "package": "com.example.app",
  "app": "Example",
  "title": "message title",
  "body": "message body",
  "timestamp": 1760000000,
  "token": "change-me"
}
```

the token is sent inside the tls-protected websocket session. the receiver accepts only `notification.created` events with a non-empty package name after the tls fingerprint check and hello handshake succeed.

## commands

| command | description |
| --- | --- |
| `cargo run -- listen` | start the linux secure websocket receiver |
| `cargo run -- listen --port 9999` | start the receiver on a custom port |
| `cargo run -- listen --token change-me` | require a shared token |
| `cargo run -- listen --allow-empty-token` | disable token auth for local testing |
| `cargo run -- listen --identity-dir ~/.config/relay` | use a custom tls identity directory |
| `cargo run -- listen --no-discovery` | start without mdns service advertisement |
| `cargo test` | run receiver tests |
| `flutter test` | run mobile widget tests |
| `flutter analyze` | analyze the flutter project |
| `flutter build apk --debug` | build the android debug apk |

## verify

confirm linux desktop notifications work:

```sh
notify-send "relay test" "desktop notifications are working"
```

then trigger a new notification on the phone. if relay is connected and notification access is enabled, the notification should appear on the linux desktop.

receiver logs should look like this:

```text
INFO mdns discovery enabled service_type=_relay._tcp.local.
INFO relay listening on wss://0.0.0.0:9876
INFO receiver identity fingerprint fingerprint=0123456789ABCDEF...
INFO token authentication enabled
INFO client connected peer=192.168.1.101:47492
INFO notification received app=Example package=com.example.app title="message title"
```

## systemd

install the receiver binary:

```sh
cd receiver
cargo install --path .
```

from the repository root, install the user service and set a real token:

```sh
mkdir -p ~/.config/systemd/user
mkdir -p ~/.config/relay
cp docs/relay.env.example ~/.config/relay/env
nano ~/.config/relay/env
cp docs/relay.service ~/.config/systemd/user/relay.service
systemctl --user daemon-reload
systemctl --user enable --now relay.service
```

the service stores its persistent tls identity in `~/.config/relay`. keep that directory stable; if `cert.der` or `key.der` is deleted, the receiver fingerprint changes and android must be paired again.

check logs:

```sh
journalctl --user -u relay.service -f
```

stop or restart:

```sh
systemctl --user stop relay.service
systemctl --user restart relay.service
```

optional boot without an interactive login:

```sh
loginctl enable-linger "$USER"
```

## troubleshooting

if the phone cannot connect:

- make sure the phone and linux machine are on the same wi-fi.
- open `http://<linux-ip>:8000/` from the phone to check whether it can reach the linux machine.
- check firewall rules for ports `9876` and `8000` if serving the apk over http.
- make sure multicast dns is not blocked on the wi-fi network if auto discovery does not find the receiver.
- make sure the receiver is running before tapping `connect`.
- make sure the phone token exactly matches `RELAY_TOKEN` or `--token`.
- make sure the receiver fingerprint exactly matches the value printed by the receiver.

if no desktop notification appears:

- run `notify-send` to confirm linux notifications are visible.
- make sure do not disturb mode is off.
- make sure android notification access is enabled for relay.
- trigger a new notification; old notifications are not resent.

## development

format and test before shipping changes:

```sh
cd receiver
cargo fmt
cargo test

cd ../mobile
dart format lib
flutter analyze
flutter test
flutter build apk --debug
```

## license

relay is licensed under the MIT License. See [LICENSE](LICENSE) for details.
