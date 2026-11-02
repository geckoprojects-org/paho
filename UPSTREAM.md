# Differences from upstream Eclipse Paho

This build is based on [eclipse-paho/paho.mqtt.java](https://github.com/eclipse-paho/paho.mqtt.java)
`develop` (`bd291f15`, the state of the last upstream release 1.2.5 plus later fixes). Every
difference has an issue here; the commit that resolves it closes the issue. Fix commits touch only
client sources and tests, one topic per commit, so they can be cherry-picked to upstream as they
are.

## Fixed here

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#1](https://github.com/geckoprojects-org/paho/issues/1) | [#511](https://github.com/eclipse-paho/paho.mqtt.java/issues/511) | v3, v5 | A connect attempt without CONNACK now times out after `connectionTimeout`; before, it hung in `CONNECTING` and automatic reconnect stopped for good. |
| [#2](https://github.com/geckoprojects-org/paho/issues/2) | – | v3, v5 | The automatic reconnect state (`reconnectDelay`, `clientLock`) is kept per client instead of in static fields shared by all clients of a JVM. |
| [#3](https://github.com/geckoprojects-org/paho/issues/3) | – | v3, v5 | The reconnect cycle reschedules when `connect()` fails synchronously instead of ending silently. |
| [#4](https://github.com/geckoprojects-org/paho/issues/4) | [#493](https://github.com/eclipse-paho/paho.mqtt.java/issues/493) | v3, v5 | New option `automaticResubscribe` (default `true`): subscriptions are restored after an automatic reconnect without session. |
| [#5](https://github.com/geckoprojects-org/paho/issues/5) | – | v3, v5 | Broker host names with `_` work on Java 16+; the reflection on `java.net.URI` that failed there is no longer needed. |
| [#6](https://github.com/geckoprojects-org/paho/issues/6) | [#857](https://github.com/eclipse-paho/paho.mqtt.java/issues/857) | v5 | The OSGi bundle exports the API packages (`common`, `common.packet`, …), not only `org.eclipse.paho.mqttv5.client`. |
| [#7](https://github.com/geckoprojects-org/paho/issues/7) | [#425](https://github.com/eclipse-paho/paho.mqtt.java/issues/425), [#654](https://github.com/eclipse-paho/paho.mqtt.java/issues/654) | v3, v5 | `disconnect()` and `close()` end the automatic reconnect cycle: no reconnect after `disconnect()`, no reconnect thread after `close()`, no `NullPointerException` from `reconnect()` before `connect()`. |

## Open, confirmed

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#8](https://github.com/geckoprojects-org/paho/issues/8) | [#958](https://github.com/eclipse-paho/paho.mqtt.java/issues/958) | v3, v5 | Keep-alive pings are scheduled on the wall clock; a time jump back delays them. Workaround: pass a `ScheduledExecutorService` to the client. |

## Open, to be reproduced

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#9](https://github.com/geckoprojects-org/paho/issues/9) | [#1065](https://github.com/eclipse-paho/paho.mqtt.java/issues/1065) | v3, v5 | No reconnect after a write-activity timeout while the callback thread is blocked. |
| [#10](https://github.com/geckoprojects-org/paho/issues/10) | [#551](https://github.com/eclipse-paho/paho.mqtt.java/issues/551) | v3, v5 | "Too many publishes in progress" with sequential QoS 1/2 publishes. |
| [#11](https://github.com/geckoprojects-org/paho/issues/11) | [#828](https://github.com/eclipse-paho/paho.mqtt.java/issues/828) | v3 | No PUBACK for redelivered QoS 1 messages with a persistent session. |
| [#12](https://github.com/geckoprojects-org/paho/issues/12) | [#1098](https://github.com/eclipse-paho/paho.mqtt.java/issues/1098), [#1099](https://github.com/eclipse-paho/paho.mqtt.java/issues/1099) | v3 | Several clients in one JVM: TCP without CONNECT after a broker restart. Investigated, not reproduced so far. |

## Checked, no change needed

- Upstream [#816](https://github.com/eclipse-paho/paho.mqtt.java/issues/816) and
  [#826](https://github.com/eclipse-paho/paho.mqtt.java/issues/826) (v5 `subscribe`) are already
  fixed on upstream `develop`, which this build is based on.
- Upstream [#507](https://github.com/eclipse-paho/paho.mqtt.java/issues/507),
  [#694](https://github.com/eclipse-paho/paho.mqtt.java/issues/694),
  [#794](https://github.com/eclipse-paho/paho.mqtt.java/issues/794) (`FileLock` reflection): the file
  persistence opens on JDK 11, 17 and 25; the reflected `tryLock()` is declared in the exported
  `java.nio.channels.FileChannel`.

## Build and packaging

Not upstream contributions, but different from an upstream build:

- bnd workspace build (Gradle 9, JDK 25, Java 8 bytecode) instead of Maven/Tycho; Maven coordinates
  `org.geckoprojects.paho:org.eclipse.paho.client.mqttv3` / `org.eclipse.paho.mqttv5.client`,
  version line 1.3.x.
- Mosquitto 2 test broker in a container and renewed test certificates (the upstream ones expired
  in 2018); see the README.
- Upstream modules that are not built here (samples, trace formatter, p2 repositories) are removed.

## Contributing back

Upstream Paho Java has been inactive since 2022 (last release 1.2.5, July 2020). Should it become
active again, each fix commit referenced by a closed issue above can be offered as a pull request
by cherry-picking it onto upstream `develop`.
