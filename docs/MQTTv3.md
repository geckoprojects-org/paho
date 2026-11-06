# Eclipse Paho Java MQTTv3 Client

The Paho Java Client provides two APIs: MqttAsyncClient provides a fully asychronous API where completion of activities is notified via registered callbacks. MqttClient is a synchronous wrapper around MqttAsyncClient where functions appear synchronous to the application.

## Using the Paho Java MQTTv3 Client

### Downloading and building

This Gecko build is published as `org.geckoprojects.paho:org.eclipse.paho.client.mqttv3`; see the [README](../README.md)
for the Maven coordinates and for building and testing with `./gradlew`.

## Documentation
Reference documentation is online at: [http://www.eclipse.org/paho/files/javadoc/index.html](http://www.eclipse.org/paho/files/javadoc/index.html)

Log and Debug in the Java Client: [https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client](https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client)

## Getting Started

The included code below is a very basic sample that connects to a server and publishes a message using the MqttClient synchronous API. More extensive samples are in the `org.eclipse.paho.sample.*` modules of [upstream Paho](https://github.com/eclipse-paho/paho.mqtt.java).


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

## Adding custom headers for Websocket connection

The included code below is a extended basic sample that connects to a server with custom headers.

```
MqttClient client = new MqttClient("wss://<BROKER_URI>", "MyClient");

MqttConnectOptions connectOptions = new MqttConnectOptions();
Map<String, String> properties = new HashMap<>();
properties.put("X-Amz-CustomAuthorizer-Name", <SOME_VALUE>);
properties.put("X-Amz-CustomAuthorizer-Signature", <SOME_VALUE>);
properties.put(<SOME_KEY>, <SOME_VALUE>);
connectOptions.setCustomWebSocketHeaders(properties);

client.connect(connectOptions);

```
