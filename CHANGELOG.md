# changelog

## v0.2.0

- add secure websocket transport with persistent receiver tls identity.
- add receiver certificate fingerprint pinning on android.
- require shared-token authentication for receiver and android payloads by default.
- add mdns/dns-sd discovery so android can find the linux receiver on the same network.
- add android reconnect with exponential backoff up to 30 seconds.
- add background discovery, boot preparation, and disk-backed pending notification queue.
- add user-level systemd service and environment file examples.
- bump receiver and mobile versions to `0.2.0`.

## v0.1.0

- add linux websocket receiver for desktop notifications.
- add flutter android client backed by a native notification listener.
