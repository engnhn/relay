# relay

relay forwards android notifications to a linux desktop over the local network. the android app stores the receiver address, listens to real notification events through android's native `NotificationListenerService`, and sends each event to the linux receiver over websocket. the receiver displays the event through the standard freedesktop notification system.

v0.1.0 is intentionally small. it is built for a trusted local network and does not include authentication, pairing, qr codes, tls, cloud services, accounts, databases, docker, systemd installation, tray apps, clipboard sync, file transfer, notification actions, or ios support.

## layout

```text
receiver/  rust cli websocket receiver for linux
mobile/    flutter android app with native kotlin notification forwarding
```

## quick start

start the linux receiver:

```sh
cd receiver
cargo run -- listen
```

build the android debug apk:

```sh
cd mobile
flutter build apk --debug
```

install with adb:

```sh
adb install -r mobile/build/app/outputs/flutter-apk/app-debug.apk
```

open relay on the phone, enter the linux machine's local ip address, keep port `9876`, and tap `connect`.

## receiver

the receiver binds to `0.0.0.0:9876` by default so a phone on the same local network can reach it.

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

environment configuration:

```sh
RELAY_HOST=0.0.0.0 RELAY_PORT=9876 cargo run -- listen
```

runtime behavior:

| behavior | detail |
| --- | --- |
| protocol | websocket text messages |
| event format | json notification payloads |
| default bind | `0.0.0.0:9876` |
| shutdown | clean `Ctrl+C` handling |
| failures | malformed messages and disconnects are logged without stopping the server |
| output | linux desktop notifications through freedesktop |

## android

the flutter screen is only configuration and status. notification capture and websocket sending live in the native kotlin layer so forwarding can continue from the android notification listener using the saved connection.

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
  "timestamp": 1760000000
}
```

the receiver accepts only `notification.created` events with a non-empty package name.

## commands

| command | description |
| --- | --- |
| `cargo run -- listen` | start the linux websocket receiver |
| `cargo run -- listen --port 9999` | start the receiver on a custom port |
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
INFO relay listening on ws://0.0.0.0:9876
INFO client connected peer=192.168.1.101:47492
INFO notification received app=Example package=com.example.app title="message title"
```

## troubleshooting

if the phone cannot connect:

- make sure the phone and linux machine are on the same wi-fi.
- open `http://<linux-ip>:8000/` from the phone to check whether it can reach the linux machine.
- check firewall rules for ports `9876` and `8000` if serving the apk over http.
- make sure the receiver is running before tapping `connect`.

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
