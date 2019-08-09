# deors-devices-ambientstation-raspberry

Ambient station built on the Raspberry Pi platform with GrovePi shield and Grove sensors, Dexter Industries' GrovePi library, pi4j library, and publishing data via MQTT.

## configuring the device

The application configuration file can be fed via the `AMBIENT_PROP_FILE` environment variable or the `ambient.prop.file` JVM system property. The default configuration provided configures a station publishing data to Eclipse IoT MQTT server on topic `AmbientStation/org/location/space/default`.
