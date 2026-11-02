# Paho Java Client — Gecko build

This repository is a **Gecko projects build of the [Eclipse Paho Java client](https://github.com/eclipse-paho/paho.mqtt.java)**,
not an official Eclipse release. It carries the upstream history and adds:

- **Automatic reconnect fixes** for the v3 and v5 clients:
  - a connect attempt without CONNACK times out after `connectionTimeout`
    ([upstream #511](https://github.com/eclipse-paho/paho.mqtt.java/issues/511));
  - reconnect state is kept per client instead of in static fields;
  - the reconnect cycle reschedules when `connect()` fails synchronously;
  - subscriptions are restored after a reconnect without session (new option
    `automaticResubscribe`, default `true`).
- **A bnd workspace build** (Gradle 9, JDK 25, Java 8 bytecode) instead of Maven/Tycho, with OSGi
  manifests that export the API packages of both clients (upstream v5 exports only
  `org.eclipse.paho.mqttv5.client`).

Every difference from upstream is tracked as an issue here; [UPSTREAM.md](UPSTREAM.md) gives the
overview of what is fixed, what is open and what was checked.

The Paho Java client is an MQTT client library for the JVM. It provides two APIs: `MqttAsyncClient`,
fully asynchronous with completion via callbacks, and `MqttClient`, a synchronous wrapper around it.
Details per protocol version: [MQTTv3.md](MQTTv3.md), [MQTTv5.md](MQTTv5.md).

## Using the client

The packages and bundle symbolic names stay `org.eclipse.paho.*`, so the bundles are drop-in
replacements for the upstream ones (same API; Java 8 or newer). They are published to Maven Central
under their own coordinates:

| Client | Maven coordinates |
|---|---|
| MQTT 3.1 / 3.1.1 | `org.geckoprojects.paho:org.eclipse.paho.client.mqttv3:1.3.0` |
| MQTT 5 | `org.geckoprojects.paho:org.eclipse.paho.mqttv5.client:1.3.0` |

```xml
<dependency>
    <groupId>org.geckoprojects.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
    <version>1.3.0</version>
</dependency>
```

Exclude `org.eclipse.paho:*` should another dependency bring the upstream client along; both contain
the same classes.

## Building and testing

```
./gradlew build
```

builds both bundles (`<module>/generated/*.jar`) and runs the tests that need no broker. All other
tests run against a Mosquitto 2 broker in a container (docker or podman), with the test
certificates in `test/broker/certs`:

```
test/broker/start-broker.sh
./gradlew brokerTest
test/broker/stop-broker.sh
```

`test/broker/generate-certs.sh` renews the test certificates and the `clientkeystore.jks` of the test
modules. Snapshots and releases are built by Jenkins (`Jenkinsfile`); GitHub Actions checks licence
headers, build and tests.

## Upstream

Upstream is configured as remote `upstream`; fixes from here are offered there as well.

- Paho project: [https://www.eclipse.org/paho](https://www.eclipse.org/paho),
  [projects.eclipse.org/projects/iot.paho](https://projects.eclipse.org/projects/iot.paho)
- Upstream repository and issues: [github.com/eclipse-paho/paho.mqtt.java](https://github.com/eclipse-paho/paho.mqtt.java)
- Mailing list: [paho-dev](https://accounts.eclipse.org/mailing-list/paho-dev)
- Log and debug in the Java client: [wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client](https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client)

Licence: as upstream, EPL-2.0 or EDL-1.0 (see `LICENSE`, `epl-v20`, `edl-v10`, `NOTICE`).

## Getting Started

The included code below is a very basic sample that connects to a server and publishes a message using the MQTTv3 synchronous API. More extensive samples of the MQTTv3 and MQTTv5 asynchronous API are in the `org.eclipse.paho.sample.*` modules of [upstream Paho](https://github.com/eclipse-paho/paho.mqtt.java); this repository does not carry them.


```
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttPublishSample {

    public static void main(String[] args) {

        String topic        = "MQTT Examples";
        String content      = "Message from MqttPublishSample";
        int qos             = 2;
        String broker       = "tcp://localhost:1883";
        String clientId     = "JavaSample";
        MemoryPersistence persistence = new MemoryPersistence();

        try {
            MqttClient sampleClient = new MqttClient(broker, clientId, persistence);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            System.out.println("Connecting to broker: "+broker);
            sampleClient.connect(connOpts);
            System.out.println("Connected");
            System.out.println("Publishing message: "+content);
            MqttMessage message = new MqttMessage(content.getBytes());
            message.setQos(qos);
            sampleClient.publish(topic, message);
            System.out.println("Message published");
            sampleClient.disconnect();
            System.out.println("Disconnected");
            System.exit(0);
        } catch(MqttException me) {
            System.out.println("reason "+me.getReasonCode());
            System.out.println("msg "+me.getMessage());
            System.out.println("loc "+me.getLocalizedMessage());
            System.out.println("cause "+me.getCause());
            System.out.println("excep "+me);
            me.printStackTrace();
        }
    }
}
```
